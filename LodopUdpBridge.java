import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.swing.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.PopupMenu;
import java.awt.MenuItem;
import java.awt.Desktop;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import javax.print.*;

/**
 * LodopUdpBridge —— 局域网打印网关（纯后台版）
 *
 * 架构：
 *   App → UDP(DISCOVER/PRINT) → Java Bridge → WebSocket(CLODOP协议) → C-Lodop → 针式打印机
 *
 * 无浏览器、无HTTP服务器、无桥页面
 * Java 进程后台运行 + 系统托盘图标
 *
 * 修正点（对齐 CLodopfuncs.js 和 picking_shipping.py）：
 *   1. tid 格式：JAVA_HHMMSS_N（对齐 CLodop GetTaskID）
 *   2. pagename 字段：调拨=A4、拣货=""
 *   3. 逐条发送：每条 HTML 单独一个 WS 消息
 *   4. 打印机枚举：从 CLodop WS 获取 Printers 对象，fallback 到 javax.print
 *   5. 测试页：用 CLodop WS 协议格式（非 Lodop JS 脚本）
 *   6. CLodop 推送 JS 库：接收但不 eval，提取 Printers 对象
 *   7. CLodop 响应处理：解析 TaskID=true/false，返回 PRINT_ACK 闭环
 *
 * @author FY App
 * @date 2026-06-24
 */
public class LodopUdpBridge {

    static final int UDP_PORT = 51010;
    static final String CLODOP_WS_URL = "ws://127.0.0.1:8000/c_webskt/";

    // CLodop 协议分隔符 — 与 CLodopfuncs.js 第 9 行 DelimChar 一致
    static final String DELIM = "\f\f";

    // 递增 tid 序号（对齐 CLodop GetTaskID 格式）
    static int iBaseTask = 0;

    // 日志文件
    static File logFile;
    static FileWriter logWriter;

    /** 初始化日志文件 */
    static void initLog() {
        try {
            String appData = System.getenv("APPDATA");
            if (appData == null) appData = ".";
            File dir = new File(appData, "LodopUdpBridge");
            if (!dir.exists()) dir.mkdirs();
            logFile = new File(dir, "bridge.log");
            // 保留最近 500 行
            if (logFile.exists() && logFile.length() > 512000) {
                trimLogTail(logFile, 500);
            }
            logWriter = new FileWriter(logFile, true);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeLog()));
        } catch (Exception e) {
            System.err.println("日志初始化失败: " + e.getMessage());
        }
    }

    /** 日志写到文件和控制台 */
    static synchronized void log(String msg) {
        String line = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + msg;
        System.out.println(line);
        try {
            if (logWriter != null) {
                logWriter.write(line + "\n");
                logWriter.flush();
            }
        } catch (Exception ignored) {}
    }

    static synchronized void logErr(String msg) {
        String line = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] ERROR: " + msg;
        System.err.println(line);
        try {
            if (logWriter != null) {
                logWriter.write(line + "\n");
                logWriter.flush();
            }
        } catch (Exception ignored) {}
    }

    /** 关闭日志 */
    static void closeLog() {
        try { if (logWriter != null) logWriter.close(); } catch (Exception ignored) {}
    }

    /** 保留日志文件末尾 N 行 */
    static void trimLogTail(File f, int keepLines) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            java.util.List<String> lines = new java.util.ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
            r.close();
            int start = Math.max(0, lines.size() - keepLines);
            FileWriter w = new FileWriter(f, false);
            for (int i = start; i < lines.size(); i++) w.write(lines.get(i) + "\n");
            w.close();
        } catch (Exception ignored) {}
    }

    // 托盘图标
    static TrayIcon trayIcon;
    static List<String> printerList = new ArrayList<>();
    static boolean clodopConnected = false;

    // ============ Task ID 生成（修正 #1）============

    static String generateTaskId() {
        LocalDateTime now = LocalDateTime.now();
        iBaseTask++;
        return String.format("JAVA%02d%02d%02d_%d",
            now.getHour(), now.getMinute(), now.getSecond(), iBaseTask);
    }

    // ============ CLodop WS 协议消息构建（修正 #2,#3）============

    /**
     * 构建单条打印任务的 CLodop WS 协议消息
     * 对齐 picking_shipping.py 第 3641-3672 行的精确字段顺序
     */
    static String buildClodopMessage(String taskId, String taskName, String printerName,
                                      String orient, String pageWidth, String pageHeight,
                                      String pageName, String htmlContent) {
        return String.join(DELIM,
            "post:charset=丂",
            "tid=" + taskId,
            "act=print",
            "browseurl=JAVA_CLODOP",
            "companyname=用友汽车信息科技（上海）股份有限公司",
            "license=FA9A697F2551BCE81BD852A4EB520525347",
            "licensea=用友汽車信息科技（上海）股份有限公司",
            "licenseb=C66313BD8413BD0174C2CADD29F5380CD92",
            "licensec=Yonyou Auto Information Technology (Shanghai) Co., Ltd.",
            "licensed=941DF3639D9F5679867946141A31424B4E6",
            "top=",
            "left=",
            "width=",
            "height=",
            "printtask=" + taskName,
            "printerindex=" + printerName,
            "orient=" + orient,
            "pagewidth=" + pageWidth,
            "pageheight=" + pageHeight,
            "pagename=" + pageName,
            "printcopies=1",
            "itemcount=1",
            "1_type=4",
            "1_top=30",
            "1_left=3mm",
            "1_width=100%",
            "1_height=100%",
            "1_content=" + htmlContent,
            "1_itemstylenames=",
            "printmodenames=;left;top;width;height",
            "printstyleclassnames="
        );
    }

    // ============ 打印机枚举（修正 #4）============

    /**
     * 从 CLodop WS 获取打印机列表（推荐方式）
     * CLodop 连接后推送 JS 库代码，其中包含 Printers 对象
     */
    static List<String> listPrintersFromClodop() {
        List<String> printers = new ArrayList<>();
        try {
            String clodopJs = fetchClodopInitMessage();
            if (clodopJs == null || clodopJs.isEmpty()) {
                return listPrintersFallback();
            }

            // 解析 Printers JSON
            // 格式：Printers={"default":"5","list":[{"name":"EPSON LQ-630K ESC/P2",...}]}
            Pattern p = Pattern.compile("Printers[\\s]*=[\\s]*\\{.*?\"list\"[\\s]*:[\\s]*\\[(.*?)\\]", Pattern.DOTALL);
            Matcher m = p.matcher(clodopJs);
            if (m.find()) {
                String listJson = "[" + m.group(1) + "]";
                Pattern nameP = Pattern.compile("\"name\"[\\s]*:[\\s]*\"([^\"]+)\"");
                Matcher nameM = nameP.matcher(listJson);
                while (nameM.find()) {
                    String name = nameM.group(1);
                    printers.add(name);
                }
            }
        } catch (Exception e) {
            logErr("从 CLodop 获取打印机列表失败: " + e.getMessage());
            return listPrintersFallback();
        }
        if (printers.isEmpty()) {
            return listPrintersFallback();
        }
        return printers;
    }

    /** 从 CLodop WS 获取初始化消息（含 Printers 对象） */
    static String fetchClodopInitMessage() {
        StringBuilder initMsg = new StringBuilder();
        CountDownLatch msgLatch = new CountDownLatch(1);

        try {
            // 使用简单的 WebSocket 客户端连接 CLodop
            // CLodop 在连接后会推送 JS 库代码（含 Printers 对象）
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.WebSocket.Listener listener = new java.net.http.WebSocket.Listener() {
                StringBuilder buffer = new StringBuilder();
                @Override
                public void onOpen(java.net.http.WebSocket ws) {
                    ws.request(1);
                }
                @Override
                public CompletionStage<?> onText(java.net.http.WebSocket ws, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                        initMsg.append(buffer.toString());
                        msgLatch.countDown();
                        ws.request(1);
                    }
                    return null;
                }
                @Override
                public CompletionStage<?> onClose(java.net.http.WebSocket ws, int code, String reason) {
                    msgLatch.countDown();
                    return null;
                }
                @Override
                public void onError(java.net.http.WebSocket ws, Throwable err) {
                    msgLatch.countDown();
                }
            };

            java.net.http.WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create(CLODOP_WS_URL), listener)
                .get(5, TimeUnit.SECONDS);

            // 等待 CLodop 推送初始化消息
            boolean received = msgLatch.await(5, TimeUnit.SECONDS);
            ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done");
            return received ? initMsg.toString() : null;
        } catch (Exception e) {
            logErr("连接 CLodop WS 获取打印机列表失败: " + e.getMessage());
            return null;
        }
    }

    /** Fallback：CLodop 未启动时使用 javax.print */
    static List<String> listPrintersFallback() {
        List<String> printers = new ArrayList<>();
        for (PrintService ps : PrintServiceLookup.lookupPrintServices(null, null)) {
            printers.add(ps.getName());
        }
        return printers;
    }

    // ============ CLodop WS 客户端与响应处理（修正 #6,#7）============

    /**
     * 发送单条打印命令到 CLodop 并等待响应
     * 解析 TaskID=ResultValue 响应，true=成功/false=失败
     */
    static boolean sendToClodopAndWait(String message, int timeoutMs) {
        CountDownLatch resultLatch = new CountDownLatch(1);
        final String[] resultValue = {""};

        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.WebSocket.Listener listener = new java.net.http.WebSocket.Listener() {
                StringBuilder buffer = new StringBuilder();
                boolean initReceived = false;

                @Override
                public void onOpen(java.net.http.WebSocket ws) {
                    ws.request(1);
                }

                @Override
                public CompletionStage<?> onText(java.net.http.WebSocket ws, CharSequence data, boolean last) {
                    String msg = data.toString();
                    buffer.append(msg);

                    if (last) {
                        String fullMsg = buffer.toString();
                        buffer.setLength(0);

                        // 调试：打出 CLodop WS 收到的每一条完整消息（完整内容，不止前200字符）
                        log("[CLODOP-WS] recv (" + fullMsg.length() + " 字符): " + fullMsg);

                        // 修正 #6：CLodop 连接后先推送 JS 库代码，接收但不 eval
                        if (!initReceived && (fullMsg.contains("strWebPageID") || fullMsg.contains("Printers"))) {
                            initReceived = true;
                            log("[CLODOP-WS] 收到 CLodop 初始化消息，提取打印机列表");
                            // 提取打印机列表
                            Pattern nameP = Pattern.compile("\"name\"[\\s]*:[\\s]*\"([^\"]+)\"");
                            Matcher nameM = nameP.matcher(fullMsg);
                            synchronized (printerList) {
                                printerList.clear();
                                while (nameM.find()) {
                                    printerList.add(nameM.group(1));
                                }
                            }
                            log("[CLODOP-WS] 打印机列表已更新，共 " + printerList.size() + " 台");
                        }

                        // 修正 #7：解析 CLodop 响应 TaskID=ResultValue
                        if (fullMsg.contains("=") && !fullMsg.contains("strWebPageID") && !fullMsg.contains("Printers")) {
                            int pos = fullMsg.indexOf("=");
                            if (pos > 0 && pos < 50) {
                                resultValue[0] = fullMsg.substring(pos + 1).trim().toLowerCase();
                                log("[CLODOP-WS] 解析到响应: " + fullMsg.trim());
                                resultLatch.countDown();
                            }
                        }

                        // CLodop 返回错误消息
                        if (fullMsg.contains("ErrorMS")) {
                            logErr("[CLODOP-WS] CLodop 返回错误: " + fullMsg);
                            resultValue[0] = "error";
                            resultLatch.countDown();
                        }
                    }
                    ws.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(java.net.http.WebSocket ws, int code, String reason) {
                    resultLatch.countDown();
                    return null;
                }

                @Override
                public void onError(java.net.http.WebSocket ws, Throwable err) {
                    resultValue[0] = "error";
                    resultLatch.countDown();
                }
            };

            java.net.http.WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create(CLODOP_WS_URL), listener)
                .get(3, TimeUnit.SECONDS);

            // 等待连接就绪后发送协议消息（缩短等待到 300ms）
            Thread.sleep(300);
            log("[CLODOP-WS] 发送打印消息（" + message.length() + " 字节）");
            // 调试：打出 CLodop 协议消息的关键部分（不含完整 HTML，太长）
            int delimIdx = message.indexOf(DELIM);
            if (delimIdx > 0) {
                String header = message.substring(0, Math.min(delimIdx + 60, message.length()));
                log("[CLODOP-WS] 消息头: " + header.replace(DELIM, "|"));
            }
            ws.sendText(message, true);

            // 等待 CLodop 响应
            boolean received = resultLatch.await(timeoutMs, TimeUnit.MILLISECONDS);

            // ⚠️ 先保存结果再关闭连接 — sendClose 会触发 onError 覆盖 resultValue[0]
            String finalResult = resultValue[0];

            if (!received) {
                logErr("[CLODOP-WS] 等待 CLodop 响应超时（" + timeoutMs + "ms）");
                try { ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done"); } catch (Exception ignored) {}
                return false;
            }

            // 安全关闭（此时 resultValue 已保存到局部变量，不会被 onError 影响）
            try { ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done"); } catch (Exception ignored) {}

            log("[CLODOP-WS] CLodop 最终结果: resultValue=[" + finalResult + "]");
            boolean ok = "true".equals(finalResult);
            log("[CLODOP-WS] sendToClodopAndWait 返回: " + ok);
            return ok;

        } catch (Exception e) {
            logErr("CLodop WS 打印失败: " + e.getMessage());
            return false;
        }
    }

    // ============ UDP 消息处理 ============

    static void handleUdpMessage(DatagramSocket socket, DatagramPacket packet, String message) {
        String senderIp = packet.getAddress().getHostAddress();
        int senderPort = packet.getPort();

        try {
            // 解析 JSON 消息（简单解析，避免依赖 JSON 库）
            String cmd = extractJsonField(message, "cmd");

            if ("DISCOVER".equals(cmd)) {
                // ---- DISCOVER 响应 ----
                // 返回 IP + 打印机列表（从 CLodop 获取）
                List<String> printers;
                synchronized (printerList) {
                    printers = new ArrayList<>(printerList);
                }
                if (printers.isEmpty()) {
                    printers = listPrintersFromClodop();
                    synchronized (printerList) {
                        printerList.clear();
                        printerList.addAll(printers);
                    }
                }

                String hostname = InetAddress.getLocalHost().getHostName();
                String localIp = getLocalIp();

                // 构建响应 JSON
                StringBuilder sb = new StringBuilder();
                sb.append("{\"cmd\":\"DISCOVER_ACK\",\"hostname\":\"").append(escapeJsonStr(hostname));
                sb.append("\",\"ip\":\"").append(escapeJsonStr(localIp));
                sb.append("\",\"printers\":");
                sb.append("[");
                for (int i = 0; i < printers.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escapeJsonStr(printers.get(i))).append("\"");
                }
                sb.append("]}");

                byte[] respData = sb.toString().getBytes(StandardCharsets.UTF_8);
                DatagramPacket respPacket = new DatagramPacket(respData, respData.length,
                    packet.getAddress(), senderPort);
                socket.send(respPacket);

                log("[DISCOVER] 响应 → " + senderIp + " (打印机: " + printers.size() + " 台)");
                showNotification("发现请求", senderIp + " 请求打印服务");

            } else if ("PRINT".equals(cmd)) {
                // ---- PRINT 请求 ----
                // 手动解析 JSON（避免 JSONObject 外部依赖）
                String printer    = extractJsonField(message, "printer");
                String orient     = extractJsonField(message, "orient");
                String pageWidth  = extractJsonField(message, "pageWidth");
                String pageHeight = extractJsonField(message, "pageHeight");
                String pageName  = extractJsonField(message, "pageName");
                String taskName  = extractJsonField(message, "taskName");
                String html      = extractJsonLongField(message, "html");

                if (orient == null || orient.isEmpty()) orient = "3";
                if (pageWidth == null || pageWidth.isEmpty()) pageWidth = "220mm";
                if (pageHeight == null || pageHeight.isEmpty()) pageHeight = "15mm";
                if (pageName == null) pageName = "";
                if (taskName == null || taskName.isEmpty()) taskName = "拣货单打印";

                log("[PRINT] 收到打印任务: " + taskName + " | 打印机: " + printer + " | HTML长度: " + html.length() + " 字符");
                // 调试：把 HTML 前 500 字符打到日志
                if (html.length() > 0) {
                    log("[PRINT] HTML前500字符: " + html.substring(0, Math.min(500, html.length())));
                } else {
                    logErr("[PRINT] HTML 为空！");
                }

                if (html == null || html.isEmpty()) {
                    sendPrintAck(socket, packet.getAddress(), senderPort, false, "HTML 内容为空");
                    return;
                }

                // ⚠️ 不再在此处调用 listPrintersFromClodop()（新增 WS 连接耗时 5-10s，导致 App 超时）
                // 打印机列表在启动时已加载，打印时直接使用
                if (printerList.isEmpty()) {
                    log("[PRINT] 警告：启动时未获取到打印机列表，使用指定打印机: " + printer);
                }

                // 构建并发送 CLodop 协议消息
                String taskId = generateTaskId();
                String clodopMsg = buildClodopMessage(taskId, taskName, printer,
                    orient, pageWidth, pageHeight, pageName, html);

                log("[PRINT] CLodop 协议消息已构建，taskId=" + taskId + "，消息总长度=" + clodopMsg.length() + " 字节");
                // 打出消息头（到第一个 DELIM 后 80 字符为止），确认字段顺序
                int hLen = Math.min(clodopMsg.length(), clodopMsg.indexOf(DELIM + "tid=") + 200);
                if (hLen < 0) hLen = Math.min(clodopMsg.length(), 300);
                log("[PRINT] 消息头: " + clodopMsg.substring(0, hLen).replace(DELIM, "|"));

                boolean ok = sendToClodopAndWait(clodopMsg, 20000);

                // 返回 PRINT_ACK（修正 #7）
            if (ok) {
                sendPrintAck(socket, packet.getAddress(), senderPort, true, taskId + "=true");
                log("[PRINT] 成功 → " + senderIp + " (" + taskName + ")");
                showNotification("打印成功", taskName + " - " + printer);
            } else {
                    sendPrintAck(socket, packet.getAddress(), senderPort, false, "CLodop 返回 false");
                    log("[PRINT] 失败 → " + senderIp + " (" + taskName + ")");
                    showNotification("打印失败", taskName + " - CLodop 返回 false");
                }

            } else {
                log("[UDP] 未知命令: " + cmd + " (来自 " + senderIp + ")");
            }

        } catch (Exception e) {
            logErr("处理 UDP 消息失败: " + e.getMessage());
            try {
                sendPrintAck(socket, packet.getAddress(), senderPort, false, "Bridge 内部错误: " + e.getMessage());
            } catch (Exception ignored) {}
        }
    }

    /** 发送 PRINT_ACK 响应到 App */
    static void sendPrintAck(DatagramSocket socket, InetAddress addr, int port, boolean ok, String detail) {
        try {
            String ack = "{\"cmd\":\"PRINT_ACK\",\"ok\":" + ok + ",\"detail\":\"" + escapeJsonStr(detail) + "\"}";
            byte[] data = ack.getBytes(StandardCharsets.UTF_8);
            DatagramPacket resp = new DatagramPacket(data, data.length, addr, port);
            socket.send(resp);
            log("[PRINT] 已发送 PRINT_ACK: ok=" + ok + " detail=" + detail);
        } catch (Exception e) {
            logErr("发送 PRINT_ACK 失败: " + e.getMessage());
        }
    }

    // ============ JSON 解析辅助 ============

    /** 从 JSON 字符串中提取指定字段的值（简单解析） */
    static String extractJsonField(String json, String field) {
        // 支持 "field":"value" 和 "field": value 格式
        Pattern p = Pattern.compile("\"" + field + "\"[\\s]*:[\\s]*\"([^\"]*?)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);

        // 尝试无引号格式（如 "ok":true）
        p = Pattern.compile("\"" + field + "\"[\\s]*:[\\s]*(\\w+)");
        m = p.matcher(json);
        if (m.find()) return m.group(1);

        return null;
    }

    static String escapeJsonStr(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ============ 手动解析 JSON 长字段（如 html，内容可能含引号）============
    // 查找 "field": "value" 中的 value，正确处理 JSON 转义字符
    static String extractJsonLongField(String json, String field) {
        String pattern = "\"" + field + "\":\"";
        int startIdx = json.indexOf(pattern);
        if (startIdx < 0) return "";
        int i = startIdx + pattern.length();  // 值内容的第一个字符
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                // JSON 转义序列：正确处理 \n \r \t \\ \" \/ 等
                char next = json.charAt(i + 1);
                if (next == 'n')       sb.append('\n');
                else if (next == 'r')  sb.append('\r');
                else if (next == 't')   sb.append('\t');
                else if (next == '\\')  sb.append('\\');
                else if (next == '"')   sb.append('"');
                else if (next == '/')   sb.append('/');
                else if (next == 'u' && i + 5 < json.length()) {
                    // Unicode 转义如 \u4E02
                    try {
                        int cp = Integer.parseInt(json.substring(i + 2, i + 6), 16);
                        sb.append((char) cp);
                        i += 4;  // 跳过 4 个十六进制字符
                    } catch (NumberFormatException e) {
                        sb.append(c).append(next);  // 非法，原样保留
                    }
                } else {
                    sb.append(c).append(next);  // 未知转义，原样保留
                }
                i += 2;
                continue;
            } else if (c == '"') {
                // 找到结束引号，返回结果
                return sb.toString();
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();  // 未找到结束引号（容错）
    }

    // ============ 获取本机局域网 IP ============

    static String getLocalIp() {
        try {
            // 优先获取局域网 IP（非 127.0.0.1）
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface net = nets.nextElement();
                if (net.isLoopback() || !net.isUp()) continue;
                Enumeration<InetAddress> addrs = net.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress()) continue;
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {}
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    // ============ 系统托盘 ============

    static void initTray() {
        try {
            SystemTray tray = SystemTray.getSystemTray();
            Image icon = createTrayIcon();
            trayIcon = new TrayIcon(icon, "CLODOP 桥 51010");
            trayIcon.setImageAutoSize(true);

            PopupMenu popup = new PopupMenu();

            // 状态
            MenuItem statusItem = new MenuItem("状态: 运行中（端口 " + UDP_PORT + "）");
            statusItem.setEnabled(false);
            popup.add(statusItem);
            popup.addSeparator();

            // 查看打印机列表
            MenuItem printersItem = new MenuItem("查看打印机列表");
            printersItem.addActionListener(e -> {
                List<String> printers;
                synchronized (printerList) {
                    printers = new ArrayList<>(printerList);
                }
                String msg = printers.isEmpty() ? "无打印机（请确认 CLodop 已启动）" :
                    "打印机列表（共 " + printers.size() + " 台）：\n" +
                    String.join("\n", printers);
                JOptionPane.showMessageDialog(null, msg, "打印机列表", JOptionPane.INFORMATION_MESSAGE);
            });
            popup.add(printersItem);

            // 打印测试页（修正 #5：用 CLodop WS 协议格式）
            MenuItem testItem = new MenuItem("打印测试页");
            testItem.addActionListener(e -> sendTestPage());
            popup.add(testItem);

            // 重新连接 CLodop
            MenuItem reconnectItem = new MenuItem("重新连接 CLodop");
            reconnectItem.addActionListener(e -> {
                List<String> fresh = listPrintersFromClodop();
                synchronized (printerList) {
                    printerList.clear();
                    printerList.addAll(fresh);
                }
                clodopConnected = !fresh.isEmpty();
                updateTrayTooltip();
                showNotification("重新连接",
                    clodopConnected ? "CLodop 已连接（" + fresh.size() + " 台打印机）" : "CLodop 未启动");
            });
            popup.add(reconnectItem);

            // 查看日志
            MenuItem logItem = new MenuItem("查看日志");
            logItem.addActionListener(e -> {
                try {
                    if (logFile != null && logFile.exists()) {
                        Desktop.getDesktop().open(logFile);
                    } else {
                        String path = (logFile != null) ? logFile.getAbsolutePath() : "未初始化";
                        JOptionPane.showMessageDialog(null,
                            "日志文件尚未创建。\n\n日志路径：" + path,
                            "查看日志", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception ex) {
                    // Desktop.open 失败时用记事本打开
                    try {
                        Runtime.getRuntime().exec(new String[]{"notepad.exe", logFile.getAbsolutePath()});
                    } catch (Exception ex2) {
                        JOptionPane.showMessageDialog(null,
                            "无法打开日志文件: " + ex.getMessage(),
                            "查看日志", JOptionPane.ERROR_MESSAGE);
                    }
                }
            });
            popup.add(logItem);

            popup.addSeparator();

            // 退出
            MenuItem exitItem = new MenuItem("退出");
            exitItem.addActionListener(e -> {
                tray.remove(trayIcon);
                System.exit(0);
            });
            popup.add(exitItem);

            trayIcon.setPopupMenu(popup);
            tray.add(trayIcon);
            System.out.println("✓ 系统托盘已创建");
        } catch (Exception e) {
            System.err.println("托盘初始化失败: " + e.getMessage());
        }
    }

    static Image createTrayIcon() {
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB) {
            {
                Graphics2D g = createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(clodopConnected ? new Color(76, 175, 80) : new Color(244, 67, 54));
                g.fillOval(1, 1, 14, 14);
                g.setColor(Color.WHITE);
                g.setFont(new Font("Arial", Font.BOLD, 10));
                g.drawString("P", 4, 13);
                g.dispose();
            }
        };
    }

    static void updateTrayTooltip() {
        if (trayIcon != null) {
            trayIcon.setToolTip("LodopUdpBridge —— " +
                (clodopConnected ? "CLodop 已连接" : "CLodop 未连接"));
        }
    }

    static void showNotification(String title, String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    // ============ 测试页（修正 #5：用 CLodop WS 协议格式）============

    static void sendTestPage() {
        List<String> printers;
        synchronized (printerList) {
            printers = new ArrayList<>(printerList);
        }
        if (printers.isEmpty()) {
            printers = listPrintersFromClodop();
            synchronized (printerList) {
                printerList.clear();
                printerList.addAll(printers);
            }
        }

        if (printers.isEmpty()) {
            JOptionPane.showMessageDialog(null,
                "未发现打印机！请确认 CLodop 已启动且打印机已安装。",
                "打印测试页", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 选择打印机
        String selected = (String) JOptionPane.showInputDialog(null,
            "选择打印机：", "打印测试页",
            JOptionPane.QUESTION_MESSAGE, null,
            printers.toArray(), printers.get(0));

        if (selected == null) return;

        // 构建测试页 HTML（与 CLodop WS 协议格式一致）
        String testHtml = "<div style=\"font-family:'微软雅黑';color:#000000;text-align:center;\">" +
            "<h2>打印测试页</h2>" +
            "<p>打印机：" + selected + "</p>" +
            "<p>时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "</p>" +
            "<hr style=\"border:1px solid #000000;\">" +
            "<table border=\"1\" style=\"width:80%;margin:0 auto;border-collapse:collapse;border:1px solid #000000;\">" +
            "<tr><td style=\"border:1px solid #000000;padding:5px;\">测试行1</td>" +
            "<td style=\"border:1px solid #000000;padding:5px;\">测试数据</td></tr>" +
            "<tr><td style=\"border:1px solid #000000;padding:5px;\">测试行2</td>" +
            "<td style=\"border:1px solid #000000;padding:5px;\">测试数据</td></tr>" +
            "</table></div>";

        String taskId = generateTaskId();
        String message = buildClodopMessage(taskId, "打印测试页", selected,
            "1", "0", "0", "A4", testHtml);

        boolean ok = sendToClodopAndWait(message, 10000);

        if (ok) {
            JOptionPane.showMessageDialog(null, "测试页打印成功！", "打印测试页", JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(null,
                "测试页打印失败！请确认 CLodop 服务已启动且打印机可用。",
                "打印测试页", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ============ 主入口 ============

    public static void main(String[] args) {
        int udpPort = UDP_PORT;

        for (int i = 0; i < args.length; i++) {
            if ("--udp".equals(args[i]) && i + 1 < args.length) {
                udpPort = Integer.parseInt(args[i + 1]);
            }
        }

        // 初始化日志文件（必须最先调用）
        initLog();

        log("=================================================");
        log("  LodopUdpBridge —— 局域网打印网关（纯后台版）");
        log("  UDP 端口: " + udpPort);
        log("  C-Lodop: " + CLODOP_WS_URL);
        log("  无浏览器桥页面，纯后台运行 + 系统托盘");
        log("  日志文件: " + (logFile != null ? logFile.getAbsolutePath() : "未初始化"));
        log("=================================================");

        // 启动时先获取打印机列表
        log("正在连接 CLodop 获取打印机列表...");
        List<String> printers = listPrintersFromClodop();
        synchronized (printerList) {
            printerList.addAll(printers);
        }
        clodopConnected = !printers.isEmpty();
        if (clodopConnected) {
            log("✓ CLodop 已连接（" + printers.size() + " 台打印机）");
            for (String p : printers) {
                log("  - " + p);
            }
        } else {
            log("⚠ CLodop 未启动，使用系统打印机列表（" + printerList.size() + " 台）");
            log("  请先启动 C-Lodop（运行 CLodop打印服务.exe）");
        }

        // 启动系统托盘
        if (SystemTray.isSupported()) {
            initTray();
            updateTrayTooltip();
        } else {
            log("⚠ 系统不支持托盘图标");
        }

        // 启动 UDP 监听（主线程）
        startUdpServer(udpPort);
    }

    // ============ UDP 服务器 ============

    static void startUdpServer(int port) {
        log("正在启动 UDP 监听（端口 " + port + "）...");
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(0);
            log("✓ UDP 监听已启动，等待 App 发送 DISCOVER/PRINT 消息...");
            log("");

            byte[] buf = new byte[65535];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                String message = new String(
                    packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                String senderIp = packet.getAddress().getHostAddress();
                log("[UDP] 收到来自 " + senderIp +
                    " 的消息（" + message.length() + " 字节） | cmd=" + extractJsonField(message, "cmd"));

                // 在新线程处理，避免阻塞接收
                new Thread(() -> handleUdpMessage(socket, packet, message), "UdpHandler").start();
            }
        } catch (SocketException e) {
            logErr("UDP Socket 错误: " + e.getMessage());
            logErr("请检查端口 " + port + " 是否被占用。");
            showNotification("LodopUdpBridge 错误", "UDP 端口 " + port + " 被占用");
        } catch (IOException e) {
            logErr("IO 错误: " + e.getMessage());
        }
    }
}
