// UniversalPrintBridge.java
// 通用打印网关 - Windows 原生打印服务 (v2.1)
// 编译: javac -cp ".;lib/pdfbox-2.0.30.jar;lib/fontbox-2.0.30.jar;lib/commons-logging-1.2.jar" UniversalPrintBridge.java
// 运行: java -cp ".;lib/pdfbox-2.0.30.jar;lib/fontbox-2.0.30.jar;lib/commons-logging-1.2.jar" UniversalPrintBridge
//
// 功能:
//   - UDP 52010 端口监听安卓端打印/预览请求
//   - 自动检测内容类型: PDF / Office(doc/docx/xls/xlsx/ppt/pptx/rtf) / 图片 / 纯文本
//   - Office 文档经 LibreOffice 无头转换为 PDF 后打印/渲染预览
//   - 打印队列 + 失败自动重试(3次)
//   - 系统托盘后台运行
//   - 预览: PC 将内容渲染为每页 PNG 图片, 分片 UDP 回传安卓端逐页显示
//
// 协议(打印):
//   PRINT: {"cmd":"PRINT","prn":"HP","copies":1,"chunks":N,"size":M} + CHUNK 分片
//         响应: {"status":"READY"} → {"status":"QUEUED"} → {"status":"DONE"|"FAIL"}
// 协议(预览):
//   PREVIEW: {"cmd":"PREVIEW","prn":"HP","chunks":N,"size":M} + CHUNK 分片
//         响应: {"status":"READY"} → {"cmd":"PREVIEW_READY","pages":K,"size":S,"chunkSize":C}
//              → 多个 {"cmd":"PREVIEW_CHUNK","seq":i,"total":T} + PNG分片
//              → 安卓端若丢片发 {"cmd":"PREVIEW_MISSING","seqs":"3,7"} 触发重传

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.print.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.print.*;
import javax.print.attribute.*;
import javax.print.attribute.standard.*;
import javax.swing.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPrintable;
import org.apache.pdfbox.printing.Scaling;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;

public class UniversalPrintBridge {
    static final int UDP_PORT = 52010;
    static final int MAX_PACKET = 65507;
    static final int CHUNK_SIZE = 8000;          // 安卓→PC 上传分片
    static final int PREVIEW_CHUNK_SIZE = 6000;  // PC→安卓 预览回传分片
    static final int MAX_RETRY = 3;
    static final String LOG_DIR = System.getenv("APPDATA") + "\\LodopUdpBridge";
    static PrintWriter log;

    // 内容类型(用于实际打印/预览分发, 以字节魔术字节权威判定)
    enum ContentType { PDF, OFFICE, IMAGE, TEXT }

    // Office 转换引擎(LibreOffice), 启动时探测
    static String officeExe = null;

    // ---- 打印任务 ----
    static class PrintTask {
        final String printer;
        final byte[] data;
        final int copies;
        int retry = MAX_RETRY;
        DatagramSocket socket;
        InetAddress addr;
        int port;
        String paper, orientation;

        PrintTask(String printer, byte[] data, int copies) {
            this.printer = printer; this.data = data; this.copies = copies;
        }
    }

    // ---- 打印队列(单线程,非阻塞) ----
    static class PrintQueue {
        static final PrintQueue INSTANCE = new PrintQueue();
        final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

        PrintQueue() {
            Thread t = new Thread(() -> {
                while (true) {
                    try { queue.take().run(); } catch (Exception e) { e.printStackTrace(); }
                }
            }, "PrintQueue");
            t.setDaemon(true); t.start();
        }

        void submit(PrintTask task) {
            log("队列接收任务 prn=" + task.printer + " size=" + task.data.length);
            queue.offer(() -> execute(task));
        }

        void execute(PrintTask task) {
            boolean ok = doPrint(task);
            if (!ok && task.retry > 0) {
                task.retry--;
                log("打印失败,剩余重试=" + task.retry + " prn=" + task.printer);
                try { Thread.sleep(800); } catch (Exception e) {}
                queue.offer(() -> execute(task));
            } else {
                String msg = ok ? "DONE" : "FAIL";
                sendAck(task, ok ? "DONE" : "FAIL", msg);
                log("任务结束 status=" + msg + " prn=" + task.printer);
            }
        }

        boolean doPrint(PrintTask task) {
            ContentType ct = detectContentType(task.data);
            try {
                switch (ct) {
                    case PDF:   return printPDF(task, task.data);
                    case OFFICE:
                        if (officeExe == null) {
                            logErr("Office 转换引擎未找到, 无法打印 Office 文档(请安装 LibreOffice 或设置 OFFICE_CONVERTER)");
                            return false;
                        }
                        byte[] pdf = convertToPdf(task.data, "dat");
                        if (pdf == null) return false;
                        return printPDF(task, pdf);
                    case IMAGE: return printImage(task);
                    case TEXT:  return printText(task);
                }
            } catch (Exception e) {
                logErr("打印异常: " + e.getMessage());
            }
            return false;
        }

        // ---- PDF 打印(PDFBox) ----
        boolean printPDF(PrintTask task, byte[] data) throws Exception {
            PDDocument doc = PDDocument.load(data);
            try {
                PrinterJob job = PrinterJob.getPrinterJob();
                PrintService ps = findPrinter(task.printer);
                if (ps == null) { logErr("未找到打印机: " + task.printer); return false; }
                job.setPrintService(ps);

                PageFormat pf = buildPageFormat(doc, task.paper, task.orientation);
                if (pf == null) pf = job.defaultPage();
                PDFPrintable printable = new PDFPrintable(doc, Scaling.SCALE_TO_FIT);
                Book book = new Book();
                book.append(printable, pf, doc.getNumberOfPages());

                if (task.copies > 1) job.setCopies(task.copies);
                job.setPageable(book);
                job.print();
                return true;
            } finally {
                doc.close();
            }
        }

        // 构建打印页面格式：用户指定优先，否则按文档第一页真实尺寸/方向
        static PageFormat buildPageFormat(PDDocument doc, String paperArg, String orientArg) {
            double wPt, hPt;
            boolean landscape;
            if (paperArg != null && !paperArg.isEmpty()) {
                switch (paperArg.toUpperCase()) {
                    case "A3":     wPt = 842; hPt = 1191; break;
                    case "A5":     wPt = 420; hPt = 595;  break;
                    case "B5":     wPt = 499; hPt = 709;  break;
                    case "LETTER": wPt = 612; hPt = 792;  break;
                    case "A4":
                    default:       wPt = 595; hPt = 842;  break;
                }
                landscape = isLandscape(orientArg);
            } else if (doc != null) {
                try {
                    PDRectangle mb = doc.getPage(0).getMediaBox();
                    wPt = mb.getWidth(); hPt = mb.getHeight();
                } catch (Exception e) { wPt = 595; hPt = 842; }
                landscape = wPt > hPt;
                if (orientArg != null && !orientArg.isEmpty())
                    landscape = isLandscape(orientArg);
            } else {
                return null;
            }
            double longSide = Math.max(wPt, hPt);
            double shortSide = Math.min(wPt, hPt);
            // 物理纸张尺寸必须按方向设置: 横向=长边为宽, 纵向=长边为高。
            // 否则纵向文档被塞进横向纸, SCALE_TO_FIT 后左右大片留白, 无法铺满页面。
            double pw = landscape ? longSide : shortSide;
            double ph = landscape ? shortSide : longSide;
            Paper paper = new Paper();
            paper.setSize(pw, ph);                  // 单位 1/72 inch = pt
            paper.setImageableArea(0, 0, pw, ph);   // 用满整张纸(硬件边距由打印机驱动裁剪)
            PageFormat pf = new PageFormat();
            pf.setPaper(paper);
            pf.setOrientation(landscape ? PageFormat.LANDSCAPE : PageFormat.PORTRAIT);
            return pf;
        }

        // 方向判定: 支持英文 LANDSCAPE/PORTRAIT 与中文 横向/纵向
        static boolean isLandscape(String orientArg) {
            if (orientArg == null) return false;
            return orientArg.equalsIgnoreCase("LANDSCAPE") || orientArg.equals("横向");
        }

        // ---- 图片打印 ----
        boolean printImage(PrintTask task) throws Exception {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(task.data));
            if (img == null) { logErr("无法解析图片数据"); return false; }

            PrinterJob job = PrinterJob.getPrinterJob();
            PrintService ps = findPrinter(task.printer);
            if (ps == null) { logErr("未找到打印机: " + task.printer); return false; }
            job.setPrintService(ps);

            PageFormat pf = buildPageFormat(null, task.paper, task.orientation);
            if (pf == null) pf = job.defaultPage();
            BufferedImage finalImg = img;
            job.setPrintable((g, p, page) -> {
                if (page > 0) return Printable.NO_SUCH_PAGE;
                double scale = Math.min(
                    (double)p.getImageableWidth() / finalImg.getWidth(),
                    (double)p.getImageableHeight() / finalImg.getHeight()
                );
                int w = (int)(finalImg.getWidth() * scale);
                int h = (int)(finalImg.getHeight() * scale);
                Graphics2D g2 = (Graphics2D)g;
                g2.translate(p.getImageableX(), p.getImageableY());
                g2.drawImage(finalImg, 0, 0, w, h, null);
                return Printable.PAGE_EXISTS;
            }, pf);

            if (task.copies > 1) job.setCopies(task.copies);
            job.print();
            return true;
        }

        // ---- 文本打印 ----
        boolean printText(PrintTask task) throws Exception {
            String text = new String(task.data, StandardCharsets.UTF_8);
            PrintService ps = findPrinter(task.printer);
            if (ps == null) { logErr("未找到打印机: " + task.printer); return false; }

            DocPrintJob job = ps.createPrintJob();
            DocFlavor flavor = DocFlavor.BYTE_ARRAY.AUTOSENSE;
            Doc doc = new SimpleDoc(text.getBytes(StandardCharsets.UTF_8), flavor, null);

            PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
            if (task.copies > 1) attrs.add(new Copies(task.copies));

            job.print(doc, attrs);
            return true;
        }

        // ---- 发送 ACK ----
        void sendAck(PrintTask task, String status, String msg) {
            try {
                String json = "{\"status\":\"" + status + "\",\"msg\":\"" + escapeJson(msg) + "\"}";
                byte[] b = json.getBytes(StandardCharsets.UTF_8);
                DatagramPacket pkt = new DatagramPacket(b, b.length, task.addr, task.port);
                task.socket.send(pkt);
            } catch (Exception e) { /* best effort */ }
        }
    }

    // ==================== 分片组装(安卓→PC 上传) ====================
    static final Map<String, Assembly> assemblies = new ConcurrentHashMap<>();
    static final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ChunkWatchdog"); t.setDaemon(true); return t;
    });

    enum Mode { PRINT, PREVIEW }

    static class Assembly {
        Mode mode;
        String printer;
        int copies;
        int size;
        int total;
        byte[] buffer;
        Set<Integer> received = new LinkedHashSet<>();
        InetAddress addr;
        int port;
        DatagramSocket socket;
        long start = System.currentTimeMillis();
        int rounds = 0;
        boolean done = false;
        String paper, orientation;
    }

    static String key(InetAddress a, int p) { return a.getHostAddress() + ":" + p; }

    static void startWatchdog() {
        watchdog.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            // 上传侧
            for (Map.Entry<String, Assembly> e : assemblies.entrySet()) {
                Assembly a = e.getValue();
                if (a.done) continue;
                if (now - a.start < 2000) continue;
                synchronized (a) {
                    if (a.received.size() >= a.total) { finalizeAssembly(a); continue; }
                    List<Integer> missing = new ArrayList<>();
                    for (int i = 0; i < a.total; i++) if (!a.received.contains(i)) missing.add(i);
                    a.rounds++;
                    if (a.rounds > 3) {
                        try { sendJson(a.socket, a.addr, a.port, "{\"status\":\"FAIL\",\"msg\":\"chunk timeout\"}"); } catch (Exception ex) {}
                        assemblies.remove(e.getKey());
                        logErr("分片接收超时 prn=" + a.printer + " 缺失=" + missing.size());
                        continue;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < missing.size(); i++) { if (i > 0) sb.append(","); sb.append(missing.get(i)); }
                    try { sendJson(a.socket, a.addr, a.port, "{\"cmd\":\"MISSING\",\"seqs\":\"" + sb + "\"}"); } catch (Exception ex) {}
                    a.start = now;
                }
            }
            // 预览回传侧
            for (Map.Entry<String, PreviewSession> e : pendingPreviews.entrySet()) {
                PreviewSession s = e.getValue();
                if (now - s.start > 15000) {
                    pendingPreviews.remove(e.getKey());
                    log("预览会话超时清理: " + e.getKey());
                }
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    static void finalizeAssembly(Assembly a) {
        synchronized (a) {
            if (a.done) return;
            a.done = true;
        }
        byte[] payload = a.buffer;
        log("分片组装完成 mode=" + a.mode + " prn=" + a.printer + " size=" + payload.length);
        if (a.mode == Mode.PRINT) {
            queuePrint(a.printer, payload, a.copies, a.paper, a.orientation, a.socket, a.addr, a.port);
        } else {
            doPreview(payload, a.socket, a.addr, a.port);
        }
        assemblies.remove(key(a.addr, a.port));
    }

    static void queuePrint(String prn, byte[] payload, int copies, String paper, String orientation,
                           DatagramSocket socket, InetAddress addr, int port) {
        log("PRINT prn=" + prn + " size=" + payload.length + " copies=" + copies + " paper=" + paper + " orient=" + orientation);
        PrintTask task = new PrintTask(prn, payload, copies);
        task.socket = socket; task.addr = addr; task.port = port;
        task.paper = paper; task.orientation = orientation;
        PrintQueue.INSTANCE.submit(task);
        try {
            sendJson(socket, addr, port,
                "{\"status\":\"QUEUED\",\"prn\":\"" + escapeJson(prn) + "\",\"type\":\"" + detectContentType(payload) + "\"}");
        } catch (Exception e) { /* best effort */ }
    }

    // ==================== 内容类型检测 ====================
    static ContentType detectContentType(byte[] data) {
        if (data.length >= 4) {
            // %PDF
            if (data[0] == 0x25 && data[1] == 0x50 && data[2] == 0x44 && data[3] == 0x46)
                return ContentType.PDF;
            // PNG / JPEG
            if (data[0] == (byte)0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47)
                return ContentType.IMAGE;
            if (data[0] == (byte)0xFF && data[1] == (byte)0xD8)
                return ContentType.IMAGE;
            // GIF
            if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F')
                return ContentType.IMAGE;
            // BMP
            if (data[0] == 'B' && data[1] == 'M')
                return ContentType.IMAGE;
            // ZIP 容器(docx/xlsx/pptx 均为 zip) → Office
            if (data[0] == 'P' && data[1] == 'K' && data[2] == 0x03 && data[3] == 0x04)
                return ContentType.OFFICE;
            // OLE 复合文档(doc/xls/ppt 旧格式)
            if (data[0] == (byte)0xD0 && data[1] == (byte)0xCF && data[2] == (byte)0x11 && data[3] == (byte)0xE0)
                return ContentType.OFFICE;
            // RTF
            if (data[0] == '{' && data[1] == '\\' && data[2] == 'r' && data[3] == 't' && data[4] == 'f')
                return ContentType.OFFICE;
        }
        return ContentType.TEXT;
    }

    // ==================== Office → PDF (LibreOffice 无头) ====================
    static String findOfficeExe() {
        String env = System.getenv("OFFICE_CONVERTER");
        if (env != null && !env.isEmpty() && new File(env).exists()) return env;

        // 优先检测随安装包分发的 LibreOffice（位于 jar 同目录的 LibreOffice/program）
        try {
            java.net.URI uri = UniversalPrintBridge.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
            String jarDir = new File(uri).getParent();
            for (String rel : new String[]{
                "LibreOffice\\program\\soffice.exe",
                "LibreOffice/program/soffice.exe",
                "LibreOffice/program/soffice"
            }) {
                File f = new File(jarDir, rel);
                if (f.exists()) return f.getAbsolutePath();
            }
        } catch (Exception e) { /* 回退到系统路径 */ }

        String[] candidates = {
            "C:\\Program Files\\LibreOffice\\program\\soffice.exe",
            "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe",
            "C:\\Program Files\\LibreOffice\\program\\soffice",
            "C:\\Program Files (x86)\\LibreOffice\\program\\soffice"
        };
        for (String c : candidates) if (new File(c).exists()) return c;

        // PATH 中查找
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                for (String name : new String[]{"soffice.exe", "soffice", "libreoffice"}) {
                    File f = new File(dir, name);
                    if (f.exists()) return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    // 返回转换后 PDF 字节; 失败返回 null
    static byte[] convertToPdf(byte[] data, String baseName) {
        if (officeExe == null) return null;
        File inFile = null, outDir = null;
        try {
            outDir = new File(System.getProperty("java.io.tmpdir"), "upb_conv_" + System.nanoTime());
            outDir.mkdirs();
            inFile = new File(outDir, baseName + ".bin");
            try (FileOutputStream fos = new FileOutputStream(inFile)) { fos.write(data); }

            String ext = "bin";
            // 让 LibreOffice 按原扩展名识别(提高转换成功率)
            ContentType ct = detectContentType(data);
            if (ct == ContentType.OFFICE) {
                // 通过 zip 入口细分(可选), 这里统一用 .bin 亦可, LibreOffice 多能识别
                ext = "bin";
            }
            ProcessBuilder pb = new ProcessBuilder(
                officeExe, "--headless", "--convert-to", "pdf",
                "--outdir", outDir.getAbsolutePath(), inFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(90, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); logErr("LibreOffice 转换超时"); return null; }

            // 输出 PDF: LibreOffice 使用输入基础名
            String pdfName = inFile.getName().replaceFirst("\\.[^.]+$", "") + ".pdf";
            File pdf = new File(outDir, pdfName);
            if (!pdf.exists()) {
                // 兜底: 目录内任意 .pdf
                File[] ps = outDir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
                if (ps != null && ps.length > 0) pdf = ps[0];
            }
            if (!pdf.exists()) { logErr("LibreOffice 未生成 PDF"); return null; }
            return readAll(pdf);
        } catch (Exception e) {
            logErr("Office 转换异常: " + e.getMessage());
            return null;
        } finally {
            try { if (inFile != null) inFile.delete(); } catch (Exception ignore) {}
            try { if (outDir != null) deleteDir(outDir); } catch (Exception ignore) {}
        }
    }

    static byte[] readAll(File f) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) if (f.isDirectory()) deleteDir(f); else f.delete();
        d.delete();
    }

    // ==================== 预览渲染(内容 → 每页 PNG) ====================
    static class PreviewSession {
        byte[] blob;
        int total;
        Set<Integer> received = new LinkedHashSet<>();
        DatagramSocket socket;
        InetAddress addr;
        int port;
        long start = System.currentTimeMillis();
        int rounds = 0;
    }
    static final Map<String, PreviewSession> pendingPreviews = new ConcurrentHashMap<>();

    static void doPreview(byte[] payload, DatagramSocket socket, InetAddress addr, int port) {
        try {
            ContentType ct = detectContentType(payload);
            List<BufferedImage> pages = renderPreviewPages(payload, ct);
            byte[] blob = buildPreviewBlob(pages);
            int total = (blob.length + PREVIEW_CHUNK_SIZE - 1) / PREVIEW_CHUNK_SIZE;
            if (total == 0) total = 1;

            PreviewSession s = new PreviewSession();
            s.blob = blob; s.total = total; s.socket = socket; s.addr = addr; s.port = port;
            pendingPreviews.put(key(addr, port), s);

            sendJson(socket, addr, port,
                "{\"cmd\":\"PREVIEW_READY\",\"pages\":" + pages.size()
                + ",\"size\":" + blob.length + ",\"chunkSize\":" + PREVIEW_CHUNK_SIZE + "}");
            sendPreviewChunks(s);
            log("预览就绪 pages=" + pages.size() + " chunks=" + total);
        } catch (Exception e) {
            logErr("预览渲染失败: " + e.getMessage());
            try { sendJson(socket, addr, port, "{\"cmd\":\"PREVIEW_FAIL\",\"msg\":\""
                + escapeJson(e.getMessage()) + "\"}"); } catch (Exception ignore) {}
        }
    }

    static void sendPreviewChunks(PreviewSession s) throws Exception {
        for (int seq = 0; seq < s.total; seq++) {
            int start = seq * PREVIEW_CHUNK_SIZE;
            int end = Math.min(start + PREVIEW_CHUNK_SIZE, s.blob.length);
            byte[] chunk = Arrays.copyOfRange(s.blob, start, end);
            String json = "{\"cmd\":\"PREVIEW_CHUNK\",\"seq\":" + seq + ",\"total\":" + s.total + "}";
            byte[] pkt = buildPrefixed(json, chunk);
            s.socket.send(new DatagramPacket(pkt, pkt.length, s.addr, s.port));
            s.received.add(seq);
            Thread.sleep(2); // 避免接收端 UDP 缓冲溢出丢包
        }
    }

    static List<BufferedImage> renderPreviewPages(byte[] data, ContentType ct) throws Exception {
        List<BufferedImage> pages = new ArrayList<>();
        switch (ct) {
            case PDF: {
                try (PDDocument doc = PDDocument.load(data)) {
                    PDFRenderer r = new PDFRenderer(doc);
                    int n = doc.getNumberOfPages();
                    for (int i = 0; i < n; i++) {
                        pages.add(r.renderImageWithDPI(i, 100, ImageType.RGB));
                    }
                }
                break;
            }
            case OFFICE: {
                if (officeExe == null) throw new IOException("Office 转换引擎未找到");
                byte[] pdf = convertToPdf(data, "preview");
                if (pdf == null) throw new IOException("Office 转换失败");
                try (PDDocument doc = PDDocument.load(pdf)) {
                    PDFRenderer r = new PDFRenderer(doc);
                    int n = doc.getNumberOfPages();
                    for (int i = 0; i < n; i++) {
                        pages.add(r.renderImageWithDPI(i, 100, ImageType.RGB));
                    }
                }
                break;
            }
            case IMAGE: {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                if (img == null) throw new IOException("图片解析失败");
                // 缩放到适合手机宽度
                int maxW = 1200;
                if (img.getWidth() > maxW) {
                    int h = img.getHeight() * maxW / img.getWidth();
                    BufferedImage scaled = new BufferedImage(maxW, h, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = scaled.createGraphics();
                    g.drawImage(img, 0, 0, maxW, h, null);
                    g.dispose();
                    img = scaled;
                }
                pages.add(img);
                break;
            }
            case TEXT: {
                String text = new String(data, StandardCharsets.UTF_8);
                pages.add(renderTextImage(text));
                break;
            }
        }
        return pages;
    }

    static BufferedImage renderTextImage(String text) {
        int maxW = 1100;
        int pad = 24;
        int fontSize = 16;
        int lineH = 22;
        Font font = new Font("Monospaced", Font.PLAIN, fontSize);
        java.awt.FontMetrics fm = new java.awt.Canvas().getFontMetrics(font);

        String[] raw = text.split("\n", -1);
        List<String> lines = new ArrayList<>();
        int maxLineChars = Math.max(10, (maxW - 2 * pad) / fm.charWidth('M'));
        for (String r : raw) {
            if (r.isEmpty()) { lines.add(""); continue; }
            // 按宽度折行
            int idx = 0;
            while (idx < r.length()) {
                int end = Math.min(idx + maxLineChars, r.length());
                lines.add(r.substring(idx, end));
                idx = end;
            }
        }
        int imgH = Math.min(8000, pad * 2 + lines.size() * lineH);
        BufferedImage img = new BufferedImage(maxW, Math.max(imgH, 200), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(font);
        int y = pad + fm.getAscent();
        int shown = (imgH - 2 * pad) / lineH;
        for (int i = 0; i < lines.size() && i < shown; i++) {
            g.drawString(lines.get(i), pad, y);
            y += lineH;
        }
        if (lines.size() > shown) {
            g.drawString("...(共 " + lines.size() + " 行)", pad, y);
        }
        g.dispose();
        return img;
    }

    // blob 格式: 每页 [4字节大端长度][PNG 字节]
    static byte[] buildPreviewBlob(List<BufferedImage> pages) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (BufferedImage pg : pages) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(pg, "PNG", png);
            byte[] p = png.toByteArray();
            int len = p.length;
            bos.write((len >>> 24) & 0xFF);
            bos.write((len >>> 16) & 0xFF);
            bos.write((len >>> 8) & 0xFF);
            bos.write(len & 0xFF);
            bos.write(p);
        }
        return bos.toByteArray();
    }

    // ==================== 工具方法 ====================

    // 查找指定名称的打印机
    static PrintService findPrinter(String name) {
        PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);
        if (name != null && !name.isEmpty()) {
            for (PrintService ps : all) if (ps.getName().equals(name)) return ps;
            for (PrintService ps : all)
                if (ps.getName().toLowerCase().contains(name.toLowerCase())) return ps;
        }
        return PrintServiceLookup.lookupDefaultPrintService();
    }

    // 列出所有打印机
    static String listPrinters() {
        PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);
        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < all.length; i++) {
            PrintService ps = all[i];
            boolean isDef = def != null && ps.getName().equals(def.getName());
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(escapeJson(ps.getName())).append("\"");
            sb.append(",\"default\":").append(isDef).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    // JSON 字符串转义
    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // 输出 JSON 响应
    static void sendJson(DatagramSocket socket, InetAddress addr, int port, String json) throws Exception {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        DatagramPacket pkt = new DatagramPacket(b, b.length, addr, port);
        socket.send(pkt);
    }

    // 解析带 4 字节大端长度前缀的包: [4字节JSON长度][JSON][二进制数据]
    // 返回 [json字符串, json结束偏移(=4+jsonLen)字符串]
    static String[] parsePrefixed(byte[] data, int len) {
        if (len < 4) return null;
        int jl = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
               | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (jl < 0 || 4 + jl > len) return null;
        String json = new String(data, 4, jl, StandardCharsets.UTF_8);
        return new String[]{ json, String.valueOf(4 + jl) };
    }

    // 构造带长度前缀的包 [4字节JSON长度][JSON][二进制]
    static byte[] buildPrefixed(String json, byte[] binary) {
        byte[] jb = json.getBytes(StandardCharsets.UTF_8);
        byte[] pkt = new byte[4 + jb.length + (binary != null ? binary.length : 0)];
        int jl = jb.length;
        pkt[0] = (byte) ((jl >>> 24) & 0xFF);
        pkt[1] = (byte) ((jl >>> 16) & 0xFF);
        pkt[2] = (byte) ((jl >>> 8) & 0xFF);
        pkt[3] = (byte) (jl & 0xFF);
        System.arraycopy(jb, 0, pkt, 4, jb.length);
        if (binary != null) System.arraycopy(binary, 0, pkt, 4 + jb.length, binary.length);
        return pkt;
    }

    // 从数据包中分离 JSON 指令和二进制数据
    static String[] parseCommand(byte[] data, int len) {
        String text = new String(data, 0, len, StandardCharsets.UTF_8);
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0, end = start;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
        }
        String json = text.substring(start, end);
        int jsonByteLen = json.getBytes(StandardCharsets.UTF_8).length;
        String binInfo = jsonByteLen < len ? String.valueOf(jsonByteLen) : "";
        return new String[]{json, binInfo};
    }

    static String jsonGet(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) return m.group(1);
        pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) return m.group(1);
        pattern = "\"" + key + "\"\\s*:\\s*(true|false)";
        m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    static String jsonGetLongField(String json, String field) {
        String pattern = "\"" + field + "\"\\s*:\\s*\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = idx + pattern.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                if (n == '"') { sb.append('"'); i++; }
                else if (n == '\\') { sb.append('\\'); i++; }
                else if (n == 'n') { sb.append('\n'); i++; }
                else if (n == 'r') { sb.append('\r'); i++; }
                else if (n == 't') { sb.append('\t'); i++; }
                else sb.append(c);
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String getLocalIP() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) { /* fallback */ }
        try { return InetAddress.getLocalHost().getHostAddress(); }
        catch (Exception e) { return "127.0.0.1"; }
    }

    // ==================== 日志 ====================
    static void log(String msg) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
        String line = "[" + time + " UPRINT] " + msg;
        System.out.println(line);
        if (log != null) { log.println(line); log.flush(); }
    }
    static void logErr(String msg) { log("ERROR: " + msg); }

    // ==================== 系统托盘 ====================
    static void setupTray() {
        if (!SystemTray.isSupported()) return;
        try {
            SystemTray tray = SystemTray.getSystemTray();
            Image img = createTrayIcon();
            PopupMenu menu = new PopupMenu();

            MenuItem infoItem = new MenuItem("通用打印网关 v2.1");
            infoItem.setEnabled(false);
            menu.add(infoItem);
            menu.addSeparator();

            MenuItem printersItem = new MenuItem("查看打印机");
            printersItem.addActionListener(e -> {
                try {
                    PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);
                    StringBuilder sb = new StringBuilder();
                    sb.append("已检测到 ").append(all.length).append(" 台打印机:\n\n");
                    for (PrintService ps : all) sb.append("• ").append(ps.getName()).append("\n");
                    JOptionPane.showMessageDialog(null, sb.toString(),
                        "打印机列表", JOptionPane.INFORMATION_MESSAGE);
                    log("查看打印机: 共 " + all.length + " 台");
                } catch (Exception ex) {
                    logErr("无法显示打印机列表窗口: " + ex.getMessage());
                }
            });
            menu.add(printersItem);

            MenuItem convItem = new MenuItem("转换引擎状态");
            convItem.addActionListener(e -> {
                String msg = (officeExe != null)
                    ? "Office 转换引擎:\n" + officeExe
                    : "未检测到 LibreOffice\n\n请安装 LibreOffice 或设置环境变量\nOFFICE_CONVERTER 指向 soffice.exe\n(Word/Excel/PPT 打印与预览需此引擎)";
                JOptionPane.showMessageDialog(null, msg, "转换引擎", JOptionPane.INFORMATION_MESSAGE);
            });
            menu.add(convItem);

            menu.addSeparator();
            MenuItem exitItem = new MenuItem("退出");
            exitItem.addActionListener(e -> { System.exit(0); });
            menu.add(exitItem);

            TrayIcon icon = new TrayIcon(img, "通用打印 52010", menu);
            icon.setImageAutoSize(true);
            tray.add(icon);
            log("系统托盘已启动");
        } catch (Exception e) {
            logErr("托盘初始化失败: " + e.getMessage());
        }
    }

    static Image createTrayIcon() {
        int w = 16, h = 16;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(34, 139, 34));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 11));
        g.drawString("P", 3, 13);
        g.dispose();
        return img;
    }

    // ==================== 主函数 ====================
    public static void main(String[] args) {
        try {
            new File(LOG_DIR).mkdirs();
            log = new PrintWriter(new FileWriter(LOG_DIR + "\\universal_bridge.log", true), true);
            log("=== 通用打印网关启动 (UDP:" + UDP_PORT + ") ===");
            log("JRE: " + System.getProperty("java.version"));
            log("打印机数量: " + PrintServiceLookup.lookupPrintServices(null, null).length);

            officeExe = findOfficeExe();
            log("Office 转换引擎: " + (officeExe != null ? officeExe : "未找到(Office 文档需安装 LibreOffice)"));

            setupTray();
            startWatchdog();

            DatagramSocket socket = new DatagramSocket(UDP_PORT);
            log("UDP 监听端口 " + UDP_PORT);
            byte[] buf = new byte[MAX_PACKET];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                InetAddress addr = packet.getAddress();
                int rport = packet.getPort();
                int len = packet.getLength();

                String[] parsed = parseCommand(buf, len);
                if (parsed == null) continue;
                String cmdJson = parsed[0];
                String cmd = jsonGet(cmdJson, "cmd");
                // 带二进制长度前缀的包(CHUNK)改用前缀解析, 避免 UTF-8 往返导致偏移错位
                if ("CHUNK".equals(cmd)) {
                    String[] pre = parsePrefixed(buf, len);
                    if (pre != null) parsed = pre;
                }

                if ("DISCOVER".equals(cmd)) {
                    String hostname = InetAddress.getLocalHost().getHostName();
                    String ip = getLocalIP();
                    String printers = listPrinters();
                    String resp = "{\"cmd\":\"DISCOVER_ACK\",\"hostname\":\"" + escapeJson(hostname)
                        + "\",\"ip\":\"" + ip + "\",\"port\":" + UDP_PORT
                        + ",\"printers\":" + printers + "}";
                    sendJson(socket, addr, rport, resp);
                    log("DISCOVER from " + addr.getHostAddress() + ":" + rport + " -> ip=" + ip);

                } else if ("LIST".equals(cmd)) {
                    String printers = listPrinters();
                    String resp = "{\"cmd\":\"LIST\",\"printers\":" + printers + "}";
                    sendJson(socket, addr, rport, resp);
                    log("LIST -> " + addr.getHostAddress() + ":" + rport);

                } else if ("PRINT".equals(cmd) || "PREVIEW".equals(cmd)) {
                    String prn = jsonGet(cmdJson, "prn");
                    if (prn == null || prn.isEmpty()) prn = jsonGet(cmdJson, "printer");
                    if (prn == null || prn.isEmpty()) prn = "";
                    String copiesStr = jsonGet(cmdJson, "copies");
                    int copies = (copiesStr != null) ? Integer.parseInt(copiesStr) : 1;

                    String chunksStr = jsonGet(cmdJson, "chunks");
                    String sizeStr = jsonGet(cmdJson, "size");
                    if (chunksStr != null && sizeStr != null) {
                        int total = Integer.parseInt(chunksStr);
                        int size = Integer.parseInt(sizeStr);
                        Assembly a = new Assembly();
                        a.mode = "PREVIEW".equals(cmd) ? Mode.PREVIEW : Mode.PRINT;
                        a.printer = prn; a.copies = copies;
                        a.paper = jsonGet(cmdJson, "paper");
                        a.orientation = jsonGet(cmdJson, "orientation");
                        a.size = size; a.total = total;
                        a.buffer = new byte[size];
                        a.addr = addr; a.port = rport; a.socket = socket;
                        a.start = System.currentTimeMillis();
                        assemblies.put(key(addr, rport), a);
                        sendJson(socket, addr, rport, "{\"status\":\"READY\",\"chunks\":" + total + "}");
                        log(cmd + " 分片模式 prn=" + prn + " chunks=" + total + " size=" + size);
                        continue;
                    }
                    sendJson(socket, addr, rport, "{\"status\":\"FAIL\",\"msg\":\"no chunks info\"}");

                } else if ("CHUNK".equals(cmd)) {
                    String seqStr = jsonGet(cmdJson, "seq");
                    if (seqStr == null) continue;
                    int seq = Integer.parseInt(seqStr);
                    Assembly a = assemblies.get(key(addr, rport));
                    if (a == null) { log("忽略孤立分片 seq=" + seq); continue; }
                    if (parsed.length > 1 && !parsed[1].isEmpty()) {
                        int jsonEnd = Integer.parseInt(parsed[1]);
                        int chunkLen = len - jsonEnd;
                        if (chunkLen > 0) {
                            synchronized (a) {
                                int offset = seq * CHUNK_SIZE;
                                if (offset + chunkLen <= a.buffer.length) {
                                    System.arraycopy(buf, jsonEnd, a.buffer, offset, chunkLen);
                                    a.received.add(seq);
                                }
                            }
                            if (a.received.size() >= a.total) finalizeAssembly(a);
                        }
                    }

                } else if ("PREVIEW_MISSING".equals(cmd)) {
                    String seqs = jsonGet(cmdJson, "seqs");
                    PreviewSession s = pendingPreviews.get(key(addr, rport));
                    if (s == null) continue;
                    if (seqs == null || seqs.isEmpty()) continue;
                    s.rounds++;
                    if (s.rounds > 5) { pendingPreviews.remove(key(addr, rport)); continue; }
                    for (String t : seqs.split(",")) {
                        try {
                            int seq = Integer.parseInt(t.trim());
                            if (seq < 0 || seq >= s.total) continue;
                            int start = seq * PREVIEW_CHUNK_SIZE;
                            int end = Math.min(start + PREVIEW_CHUNK_SIZE, s.blob.length);
                            byte[] chunk = Arrays.copyOfRange(s.blob, start, end);
                            String json = "{\"cmd\":\"PREVIEW_CHUNK\",\"seq\":" + seq + ",\"total\":" + s.total + "}";
                            byte[] pkt = buildPrefixed(json, chunk);
                            s.socket.send(new DatagramPacket(pkt, pkt.length, s.addr, s.port));
                            Thread.sleep(1);
                        } catch (Exception ignore) {}
                    }
                    log("重传预览分片: " + seqs);

                } else {
                    sendJson(socket, addr, rport, "{\"status\":\"FAIL\",\"msg\":\"unknown cmd: " + escapeJson(String.valueOf(cmd)) + "\"}");
                }
            }
        } catch (Exception e) {
            logErr("FATAL: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
