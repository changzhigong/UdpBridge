// UniversalPrintBridge.java
// 通用打印网关 - Windows 原生打印服务
// 编译: javac -cp ".;lib/pdfbox-2.0.30.jar;lib/fontbox-2.0.30.jar" UniversalPrintBridge.java
// 运行: java -cp ".;lib/pdfbox-2.0.30.jar;lib/fontbox-2.0.30.jar" UniversalPrintBridge
//
// 功能:
//   - UDP 52010 端口监听安卓端打印请求
//   - 自动检测打印类型(PDF / PNG / JPEG / TEXT)，无需 type 字段
//   - 打印队列 + 失败自动重试(3次)
//   - 打印前预览(Swing 窗口)
//   - 系统托盘后台运行
//   - Windows 原生 javax.print 驱动打印机
//
// 协议:
//   - LIST:   {"cmd":"LIST"}
//             响应: {"printers":[{"name":"HP","default":true},...]}
//   - PRINT:  {"cmd":"PRINT","prn":"HP","copies":1} + 原始打印数据
//             响应: {"status":"QUEUED","prn":"HP"} / {"status":"DONE"} / {"status":"FAIL","msg":"..."}
//   - PREVIEW:{"cmd":"PREVIEW","prn":"HP"} + 原始打印数据
//             响应: {"status":"PREVIEW"} -> 弹出预览窗口

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.print.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.print.*;
import javax.print.attribute.*;
import javax.print.attribute.standard.*;
import javax.swing.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPrintable;
import org.apache.pdfbox.printing.Orientation;

public class UniversalPrintBridge {
    static final int UDP_PORT = 52010;
    static final int MAX_PACKET = 65507;
    static final int MAX_RETRY = 3;
    static final String LOG_DIR = System.getenv("APPDATA") + "\\LodopUdpBridge";
    static PrintWriter log;

    // ---- 打印类型 ----
    enum PrintType { PDF, IMAGE, TEXT }

    // ---- 打印任务 ----
    static class PrintTask {
        final String printer;
        final byte[] data;
        final PrintType type;
        final int copies;
        int retry = MAX_RETRY;
        DatagramSocket socket;
        InetAddress addr;
        int port;

        PrintTask(String printer, byte[] data, PrintType type, int copies) {
            this.printer = printer; this.data = data; this.type = type; this.copies = copies;
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
            log("队列接收任务 type=" + task.type + " prn=" + task.printer);
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
                String msg = ok ? "DONE" : "FAIL after retry";
                sendAck(task, ok ? "DONE" : "FAIL", msg);
                log("任务结束 status=" + msg + " prn=" + task.printer);
            }
        }

        boolean doPrint(PrintTask task) {
            try {
                switch (task.type) {
                    case PDF:   return printPDF(task);
                    case IMAGE: return printImage(task);
                    case TEXT:  return printText(task);
                }
            } catch (Exception e) {
                logErr("打印异常: " + e.getMessage());
            }
            return false;
        }

        // ---- PDF 打印(PDFBox) ----
        boolean printPDF(PrintTask task) throws Exception {
            PDDocument doc = PDDocument.load(task.data);
            try {
                PrinterJob job = PrinterJob.getPrinterJob();
                PrintService ps = findPrinter(task.printer);
                if (ps == null) { logErr("未找到打印机: " + task.printer); return false; }
                job.setPrintService(ps);

                PDFPrintable printable = new PDFPrintable(doc);
                Book book = new Book();
                book.append(printable, job.defaultPage(), doc.getNumberOfPages());

                if (task.copies > 1) {
                    job.setCopies(task.copies);
                }

                job.setPageable(book);
                job.print();
                return true;
            } finally {
                doc.close();
            }
        }

        // ---- 图片打印 ----
        boolean printImage(PrintTask task) throws Exception {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(task.data));
            if (img == null) { logErr("无法解析图片数据"); return false; }

            PrinterJob job = PrinterJob.getPrinterJob();
            PrintService ps = findPrinter(task.printer);
            if (ps == null) { logErr("未找到打印机: " + task.printer); return false; }
            job.setPrintService(ps);

            BufferedImage finalImg = img;
            job.setPrintable((g, pf, page) -> {
                if (page > 0) return Printable.NO_SUCH_PAGE;
                double scale = Math.min(
                    (double)pf.getImageableWidth() / finalImg.getWidth(),
                    (double)pf.getImageableHeight() / finalImg.getHeight()
                );
                int w = (int)(finalImg.getWidth() * scale);
                int h = (int)(finalImg.getHeight() * scale);
                Graphics2D g2 = (Graphics2D)g;
                g2.translate(pf.getImageableX(), pf.getImageableY());
                g2.drawImage(finalImg, 0, 0, w, h, null);
                return Printable.PAGE_EXISTS;
            });

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

    // ==================== 预览窗口 ====================
    static void showPreview(byte[] data, PrintType type) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("打印预览");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(750, 900);
            frame.setLocationRelativeTo(null);

            if (type == PrintType.IMAGE) {
                try {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                    if (img != null) {
                        JLabel label = new JLabel(new ImageIcon(img));
                        frame.add(new JScrollPane(label));
                    } else {
                        frame.add(new JLabel("图片加载失败", JLabel.CENTER));
                    }
                } catch (Exception e) {
                    frame.add(new JLabel("图片加载失败: " + e.getMessage(), JLabel.CENTER));
                }
            } else if (type == PrintType.PDF) {
                try {
                    PDDocument doc = PDDocument.load(data);
                    int pages = doc.getNumberOfPages();
                    doc.close();
                    JEditorPane pane = new JEditorPane("text/html",
                        "<html><body style='font-family:sans-serif;padding:40px'>" +
                        "<h2>PDF 文档</h2>" +
                        "<p>页数: " + pages + "</p>" +
                        "<p style='color:#666'>PDF不支持图文预览,将直接发送到打印机</p>" +
                        "</body></html>");
                    pane.setEditable(false);
                    frame.add(new JScrollPane(pane));
                } catch (Exception e) {
                    frame.add(new JLabel("PDF 解析失败", JLabel.CENTER));
                }
            } else {
                JTextArea area = new JTextArea();
                area.setFont(new Font("Monospaced", Font.PLAIN, 13));
                area.setEditable(false);
                area.setText(new String(data, StandardCharsets.UTF_8));
                frame.add(new JScrollPane(area));
            }
            frame.setVisible(true);
        });
    }

    // ==================== 工具方法 ====================

    // 自动检测打印类型(魔术字节)
    static PrintType detectType(byte[] data) {
        if (data.length >= 4) {
            if (data[0] == 0x25 && data[1] == 0x50 && data[2] == 0x44 && data[3] == 0x46)
                return PrintType.PDF;
            if (data[0] == (byte)0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47)
                return PrintType.IMAGE;
            if (data[0] == (byte)0xFF && data[1] == (byte)0xD8)
                return PrintType.IMAGE;
        }
        return PrintType.TEXT;
    }

    // 查找指定名称的打印机
    static PrintService findPrinter(String name) {
        PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);
        for (PrintService ps : all) {
            if (ps.getName().equals(name)) return ps;
        }
        // fallback: 模糊匹配(包含)
        for (PrintService ps : all) {
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

    // 从数据包中分离 JSON 指令和二进制数据
    // 格式: {"cmd":"PRINT","prn":"HP"}\r\n 或 {\"cmd\":\"LIST\"}
    static String[] parseCommand(byte[] data, int len) {
        String text = new String(data, 0, len, StandardCharsets.UTF_8);
        // 找 JSON 对象(第一个 { 到匹配的 })
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0, end = start;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
        }
        String json = text.substring(start, end);
        // JSON 后面的字节是二进制打印数据
        int jsonByteLen = json.getBytes(StandardCharsets.UTF_8).length;
        String binInfo = jsonByteLen < len ? String.valueOf(jsonByteLen) : "";
        return new String[]{json, binInfo};
    }

    // 简易 JSON 取值(无外部依赖)
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

            MenuItem infoItem = new MenuItem("通用打印网关 v2.0");
            infoItem.setEnabled(false);
            menu.add(infoItem);
            menu.addSeparator();

            MenuItem printersItem = new MenuItem("查看打印机");
            printersItem.addActionListener(e -> {
                String list = listPrinters();
                log("打印机列表: " + list);
            });
            menu.add(printersItem);

            menu.addSeparator();
            MenuItem exitItem = new MenuItem("退出");
            exitItem.addActionListener(e -> { System.exit(0); });
            menu.add(exitItem);

            TrayIcon icon = new TrayIcon(img, "通用打印网关(UDP:" + UDP_PORT + ")", menu);
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
        g.setColor(new Color(34, 139, 34)); // 绿色,区分于 CLODOP 蓝色
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
            // 初始化日志
            new File(LOG_DIR).mkdirs();
            log = new PrintWriter(new FileWriter(LOG_DIR + "\\universal_bridge.log", true), true);
            log("=== 通用打印网关启动 (UDP:" + UDP_PORT + ") ===");
            log("JRE: " + System.getProperty("java.version"));
            log("打印机数量: " + PrintServiceLookup.lookupPrintServices(null, null).length);

            // 系统托盘
            setupTray();

            // UDP 服务
            DatagramSocket socket = new DatagramSocket(UDP_PORT);
            log("UDP 监听端口 " + UDP_PORT);
            byte[] buf = new byte[MAX_PACKET];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                InetAddress addr = packet.getAddress();
                int rport = packet.getPort();
                int len = packet.getLength();

                // 解析指令
                String[] parsed = parseCommand(buf, len);
                if (parsed == null) continue;
                String cmdJson = parsed[0];
                String cmd = jsonGet(cmdJson, "cmd");

                if ("LIST".equals(cmd)) {
                    // 列出打印机
                    String printers = listPrinters();
                    String resp = "{\"cmd\":\"LIST\",\"printers\":" + printers + "}";
                    sendJson(socket, addr, rport, resp);
                    log("LIST -> " + addr.getHostAddress() + ":" + rport);

                } else if ("PRINT".equals(cmd)) {
                    // 打印任务
                    String prn = jsonGet(cmdJson, "prn");
                    if (prn == null || prn.isEmpty()) {
                        sendJson(socket, addr, rport, "{\"status\":\"FAIL\",\"msg\":\"missing prn\"}");
                        continue;
                    }
                    String copiesStr = jsonGet(cmdJson, "copies");
                    int copies = (copiesStr != null) ? Integer.parseInt(copiesStr) : 1;

                    // 提取二进制数据(JSON 后面部分)
                    int jsonEnd = Integer.parseInt(parsed[1]);
                    byte[] payload = new byte[len - jsonEnd];
                    System.arraycopy(buf, jsonEnd, payload, 0, payload.length);

                    PrintType type = detectType(payload);
                    log("PRINT prn=" + prn + " type=" + type + " size=" + payload.length + " copies=" + copies);

                    PrintTask task = new PrintTask(prn, payload, type, copies);
                    task.socket = socket; task.addr = addr; task.port = rport;
                    PrintQueue.INSTANCE.submit(task);
                    sendJson(socket, addr, rport, "{\"status\":\"QUEUED\",\"prn\":\"" + escapeJson(prn) + "\",\"type\":\"" + type + "\"}");

                } else if ("PREVIEW".equals(cmd)) {
                    // 预览(不打印)
                    String prn = jsonGet(cmdJson, "prn");
                    int jsonEnd = Integer.parseInt(parsed[1]);
                    byte[] payload = new byte[len - jsonEnd];
                    System.arraycopy(buf, jsonEnd, payload, 0, payload.length);

                    PrintType type = detectType(payload);
                    log("PREVIEW type=" + type + " size=" + payload.length);
                    sendJson(socket, addr, rport, "{\"status\":\"PREVIEW\",\"type\":\"" + type + "\"}");
                    showPreview(payload, type);

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
