// UniversalPrintBridge.java
// 通用打印网关 - Windows 原生打印服务
// 编译: javac -cp ".;lib/pdfbox-2.0.30.jar;lib/fontbox-2.0.30.jar;lib/commons-logging-1.2.jar" UniversalPrintBridge.java
// 运行: java -cp ".;lib/pdfbox-2.0.30.jar;lib/fontbox-2.0.30.jar;lib/commons-logging-1.2.jar" UniversalPrintBridge
//
// 功能:
//   - UDP 52010 端口监听安卓端打印请求
//   - 自动检测打印类型(PDF / PNG / JPEG / TEXT)，无需 type 字段
//   - 同时支持 Base64 协议(type+data 字段)，兼容安卓端 Bitmap → Base64 一行打印
//   - 打印队列 + 失败自动重试(3次)
//   - 系统托盘后台运行
//   - Windows 原生 javax.print 驱动打印机
//   - 预览功能移至安卓端，PC 端纯后台打印
//
// 协议:
//   - DISCOVER:{"cmd":"DISCOVER"}
//             响应: {"cmd":"DISCOVER_ACK","hostname":"PC","ip":"192.168.1.x","port":52010,"printers":[...]}
//   - LIST:   {"cmd":"LIST"}
//             响应: {"cmd":"LIST","printers":[{"name":"HP","default":true},...]}
//   - PRINT:  方式A: {"cmd":"PRINT","prn":"HP","copies":1} + 原始二进制数据(JSON后)
//             方式B: {"cmd":"PRINT","type":"IMAGE","printer":"HP","copies":2,"data":"<base64>"}
//             响应: {"status":"QUEUED","prn":"HP","type":"PDF"} → {"status":"DONE"} / {"status":"FAIL"}

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
    static final int CHUNK_SIZE = 8000;
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

    // ==================== 分片组装(大文件打印) ====================
    static final Map<String, Assembly> assemblies = new ConcurrentHashMap<>();
    static final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ChunkWatchdog"); t.setDaemon(true); return t;
    });

    static class Assembly {
        String printer;
        int copies;
        PrintType type;
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
    }

    static String key(InetAddress a, int p) { return a.getHostAddress() + ":" + p; }

    static void startWatchdog() {
        watchdog.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Assembly> e : assemblies.entrySet()) {
                Assembly a = e.getValue();
                if (a.done) continue;
                if (now - a.start < 2000) continue;
                synchronized (a) {
                    if (a.received.size() >= a.total) { finalizeAssembly(a); continue; }
                    java.util.List<Integer> missing = new ArrayList<>();
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
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    static void finalizeAssembly(Assembly a) {
        synchronized (a) {
            if (a.done) return;
            a.done = true;
        }
        byte[] payload = a.buffer;
        PrintType type = a.type != null ? a.type : detectType(payload);
        log("分片组装完成 prn=" + a.printer + " type=" + type + " size=" + payload.length);
        queuePrint(a.printer, payload, type, a.copies, a.socket, a.addr, a.port);
        assemblies.remove(key(a.addr, a.port));
    }

    static void queuePrint(String prn, byte[] payload, PrintType type, int copies,
                           DatagramSocket socket, InetAddress addr, int port) {
        log("PRINT prn=" + prn + " type=" + type + " size=" + payload.length + " copies=" + copies);
        PrintTask task = new PrintTask(prn, payload, type, copies);
        task.socket = socket; task.addr = addr; task.port = port;
        PrintQueue.INSTANCE.submit(task);
        try {
            sendJson(socket, addr, port,
                "{\"status\":\"QUEUED\",\"prn\":\"" + escapeJson(prn) + "\",\"type\":\"" + type + "\"}");
        } catch (Exception e) { /* best effort */ }
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

    // 提取长字段值(如 Base64 编码的 data 字段)
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

    // 获取本机局域网 IP 地址
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

            MenuItem infoItem = new MenuItem("通用打印网关 v2.0");
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

            // 分片接收看门狗(超时缺失重传)
            startWatchdog();

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

                if ("DISCOVER".equals(cmd)) {
                    // 局域网广播发现
                    String hostname = InetAddress.getLocalHost().getHostName();
                    String ip = getLocalIP();
                    String printers = listPrinters();
                    String resp = "{\"cmd\":\"DISCOVER_ACK\",\"hostname\":\"" + escapeJson(hostname)
                        + "\",\"ip\":\"" + ip + "\",\"port\":" + UDP_PORT
                        + ",\"printers\":" + printers + "}";
                    sendJson(socket, addr, rport, resp);
                    log("DISCOVER from " + addr.getHostAddress() + ":" + rport + " -> ip=" + ip);

                } else if ("LIST".equals(cmd)) {
                    // 列出打印机
                    String printers = listPrinters();
                    String resp = "{\"cmd\":\"LIST\",\"printers\":" + printers + "}";
                    sendJson(socket, addr, rport, resp);
                    log("LIST -> " + addr.getHostAddress() + ":" + rport);

                } else if ("PRINT".equals(cmd)) {
                    // 打印任务 - 三种协议:
                    //   方式B: JSON 内嵌 Base64(type+data) —— 小数据兼容
                    //   分片模式: JSON 声明 chunks/size —— Android 主用(支持大文件)
                    //   方式A: JSON + 原始二进制(单包) —— 旧兼容(仅小文件)
                    String prn = jsonGet(cmdJson, "prn");
                    if (prn == null || prn.isEmpty()) prn = jsonGet(cmdJson, "printer");
                    if (prn == null || prn.isEmpty()) {
                        sendJson(socket, addr, rport, "{\"status\":\"FAIL\",\"msg\":\"missing printer\"}");
                        continue;
                    }
                    String copiesStr = jsonGet(cmdJson, "copies");
                    int copies = (copiesStr != null) ? Integer.parseInt(copiesStr) : 1;

                    // 方式B: Base64 内嵌数据
                    String typeStr = jsonGet(cmdJson, "type");
                    String base64 = jsonGetLongField(cmdJson, "data");
                    if (typeStr != null && base64 != null && !base64.isEmpty()) {
                        byte[] payload;
                        try {
                            payload = Base64.getDecoder().decode(base64);
                        } catch (Exception e) {
                            sendJson(socket, addr, rport, "{\"status\":\"FAIL\",\"msg\":\"base64 decode error\"}");
                            continue;
                        }
                        PrintType t = "PDF".equalsIgnoreCase(typeStr) ? PrintType.PDF
                                : "IMAGE".equalsIgnoreCase(typeStr) ? PrintType.IMAGE : PrintType.TEXT;
                        queuePrint(prn, payload, t, copies, socket, addr, rport);
                        continue;
                    }

                    // 分片模式(Android 主用)
                    String chunksStr = jsonGet(cmdJson, "chunks");
                    String sizeStr = jsonGet(cmdJson, "size");
                    if (chunksStr != null && sizeStr != null) {
                        int total = Integer.parseInt(chunksStr);
                        int size = Integer.parseInt(sizeStr);
                        PrintType t = null;
                        if (typeStr != null) {
                            t = "PDF".equalsIgnoreCase(typeStr) ? PrintType.PDF
                                : "IMAGE".equalsIgnoreCase(typeStr) ? PrintType.IMAGE : PrintType.TEXT;
                        }
                        Assembly a = new Assembly();
                        a.printer = prn; a.copies = copies; a.type = t;
                        a.size = size; a.total = total;
                        a.buffer = new byte[size];
                        a.addr = addr; a.port = rport; a.socket = socket;
                        a.start = System.currentTimeMillis();
                        assemblies.put(key(addr, rport), a);
                        sendJson(socket, addr, rport, "{\"status\":\"READY\",\"chunks\":" + total + "}");
                        log("PRINT 分片模式 prn=" + prn + " chunks=" + total + " size=" + size);
                        continue;
                    }

                    // 方式A: 单包原始二进制(仅小文件)
                    if (parsed.length > 1 && !parsed[1].isEmpty()) {
                        int jsonEnd = Integer.parseInt(parsed[1]);
                        if (jsonEnd < len) {
                            byte[] payload = new byte[len - jsonEnd];
                            System.arraycopy(buf, jsonEnd, payload, 0, payload.length);
                            queuePrint(prn, payload, detectType(payload), copies, socket, addr, rport);
                            continue;
                        }
                    }
                    sendJson(socket, addr, rport, "{\"status\":\"FAIL\",\"msg\":\"no data\"}");

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
