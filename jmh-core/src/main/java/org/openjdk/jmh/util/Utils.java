/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jmh.util;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.security.AccessControlException;
import java.util.*;
import java.util.concurrent.*;

public class Utils {

    private Utils() {

    }

    public static <T extends Comparable<T>> T min(Collection<T> ts) {
        T min = null;
        for (T t : ts) {
            if (min == null) {
                min = t;
            } else {
                min = min.compareTo(t) < 0 ? min : t;
            }
        }
        return min;
    }

    public static <T extends Comparable<T>> T max(Collection<T> ts) {
        T max = null;
        for (T t : ts) {
            if (max == null) {
                max = t;
            } else {
                max = max.compareTo(t) > 0 ? max : t;
            }
        }
        return max;
    }

    public static String[] concat(String[] t1, String[] t2) {
        String[] r = new String[t1.length + t2.length];
        System.arraycopy(t1, 0, r, 0, t1.length);
        System.arraycopy(t2, 0, r, t1.length, t2.length);
        return r;
    }

    public static String join(Collection<String> src, String delim) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String s : src) {
            if (first) {
                first = false;
            } else {
                sb.append(delim);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    public static String join(String[] src, String delim) {
        return join(Arrays.asList(src), delim);
    }

    public static Collection<String> splitQuotedEscape(String src) {
        List<String> results = new ArrayList<>();

        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (char ch : src.toCharArray()) {
            if (ch == ' ' && !escaped) {
                String s = sb.toString();
                if (!s.isEmpty()) {
                    results.add(s);
                    sb = new StringBuilder();
                }
            } else if (ch == '\"') {
                escaped ^= true;
            } else {
                sb.append(ch);
            }
        }

        String s = sb.toString();
        if (!s.isEmpty()) {
            results.add(s);
        }

        return results;
    }

    public static int sum(int[] arr) {
        int sum = 0;
        for (int i : arr) {
            sum += i;
        }
        return sum;
    }

    public static int roundUp(int v, int quant) {
        if ((v % quant) == 0) {
            return v;
        } else {
            return ((v / quant) + 1)*quant;
        }
    }

    public static String throwableToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        pw.close();
        return sw.toString();
    }

    public static int[] unmarshalIntArray(String src) {
        String[] ss = src.split("=");
        int[] arr = new int[ss.length];
        int cnt = 0;
        for (String s : ss) {
            arr[cnt] = Integer.parseInt(s.trim());
            cnt++;
        }
        return arr;
    }

    public static String marshalIntArray(int[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i : arr) {
            sb.append(i);
            sb.append("=");
        }
        return sb.toString();
    }

    /**
     * Warm up the CPU schedulers, bring all the CPUs online to get the
     * reasonable estimate of the system capacity. Some systems, notably embedded Linuxes,
     * power down the idle CPUs and so availableProcessors() may report lower CPU count
     * than would be present after the load-up.
     *
     * @return max CPU count
     */
    public static int figureOutHotCPUs() {
        ExecutorService service = Executors.newCachedThreadPool();

        int warmupTime = 1000;
        long lastChange = System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<>();
        futures.add(service.submit(new BurningTask()));

        int max = 0;
        while (System.currentTimeMillis() - lastChange < warmupTime) {
            int cur = Runtime.getRuntime().availableProcessors();
            if (cur > max) {
                max = cur;
                lastChange = System.currentTimeMillis();
                futures.add(service.submit(new BurningTask()));
            }
        }

        for (Future<?> f : futures) {
            f.cancel(true);
        }

        service.shutdown();

        return max;
    }

    private static void setAccessible(Object holder, AccessibleObject o) throws IllegalAccessException {
        // JDK 9+ has the module protections in place, which would print the warning
        // to the console if we try setAccessible(true) on inaccessible object.
        // JDK 16 would deny access by default, so we have no recourse at all.
        // Try to check with JDK 9+ AccessibleObject.canAccess before doing this
        // to avoid the confusing console warnings. Force the break in if user asks
        // explicitly.

        if (!Boolean.getBoolean("jmh.forceSetAccessible")) {
            try {
                Method canAccess = AccessibleObject.class.getDeclaredMethod("canAccess", Object.class);
                if (!(boolean) canAccess.invoke(o, holder)) {
                    throw new IllegalAccessException(o + " is not accessible");
                }
            } catch (NoSuchMethodException | InvocationTargetException e) {
                // fall-through
            }
        }

        o.setAccessible(true);
    }

    public static Charset guessConsoleEncoding() {
        // The reason for this method to exist is simple: we need the proper platform encoding for output.
        // We cannot use Console class directly, because we also need the access to the raw byte stream,
        // e.g. for pushing in a raw output from a forked VM invocation. Therefore, we are left with
        // reflectively poking out the Charset from Console, and use it for our own private output streams.
        // Since JDK 17, there is Console.charset(), which we can use reflectively.

        // Try 1. Try to poke the System.console().
        Console console = System.console();
        if (console != null) {
            try {
                Method m = Console.class.getDeclaredMethod("charset");
                Object res = m.invoke(console);
                if (res instanceof Charset) {
                    return (Charset) res;
                }
            } catch (Exception e) {
                // fall-through
            }
            try {
                Field f = Console.class.getDeclaredField("cs");
                setAccessible(console, f);
                Object res = f.get(console);
                if (res instanceof Charset) {
                    return (Charset) res;
                }
            } catch (Exception e) {
                // fall-through
            }
            try {
                Method m = Console.class.getDeclaredMethod("encoding");
                setAccessible(console, m);
                Object res = m.invoke(null);
                if (res instanceof String) {
                    return Charset.forName((String) res);
                }
            } catch (Exception e) {
                // fall-through
            }
        }

        // Try 2. Try to poke stdout.
        // When System.console() is null, that is, an application is not attached to a console, the actual
        // charset of standard output should be extracted from System.out, not from System.console().
        // If we indeed have the console, but failed to poll its charset, it is still better to poke stdout.
        try {
            PrintStream out = System.out;
            if (out != null) {
                Field f = PrintStream.class.getDeclaredField("charOut");
                setAccessible(out, f);
                Object res = f.get(out);
                if (res instanceof OutputStreamWriter) {
                    String encoding = ((OutputStreamWriter) res).getEncoding();
                    if (encoding != null) {
                        return Charset.forName(encoding);
                    }
                }
            }
        } catch (Exception e) {
            // fall-through
        }

        // Try 3. Try to poll internal properties.
        String prop = System.getProperty("sun.stdout.encoding");
        if (prop != null) {
            try {
                return Charset.forName(prop);
            } catch (Exception e) {
                // fall-through
            }
        }

        // Try 4. Nothing left to do, except for returning a (possibly mismatched) default charset.
        return Charset.defaultCharset();
    }

    public static void reflow(PrintWriter pw, String src, int width, int indent) {
        StringTokenizer tokenizer = new StringTokenizer(src);
        int curWidth = indent;
        indent(pw, indent);
        while (tokenizer.hasMoreTokens()) {
            String next = tokenizer.nextToken();
            pw.print(next);
            pw.print(" ");
            curWidth += next.length() + 1;
            if (curWidth > width) {
                pw.println();
                indent(pw, indent);
                curWidth = 0;
            }
        }
        pw.println();
    }

    private static void indent(PrintWriter pw, int indent) {
        for (int i = 0; i < indent; i++) {
            pw.print(" ");
        }
    }

    public static Collection<String> rewrap(String lines) {
        Collection<String> result = new ArrayList<>();
        String[] words = lines.split("[ \n]");
        String line = "";
        int cols = 0;
        for (String w : words) {
            cols += w.length();
            line += w + " ";
            if (cols > 40) {
                result.add(line);
                line = "";
                cols = 0;
            }
        }
        if (!line.trim().isEmpty()) {
            result.add(line);
        }
        return result;
    }

    static class BurningTask implements Runnable {
        @Override
        public void run() {
            while (!Thread.interrupted()); // burn;
        }
    }

    public static boolean isWindows() {
        return System.getProperty("os.name").contains("indows");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name").contains("Linux");
    }

    public static boolean isMacos() {
        return System.getProperty("os.name").contains("Mac");
    }

    public static String getCurrentJvm() {
        return System.getProperty("java.home") +
                File.separator +
                "bin" +
                File.separator +
                "java" +
                (isWindows() ? ".exe" : "");
    }

    public static String getCurrentJvmVersion() {
        return "JDK "
                + System.getProperty("java.version")
                + ", VM "
                + System.getProperty("java.vm.version");
    }

    public static String getCurrentOSVersion() {
        return System.getProperty("os.name")
                + ", "
                + System.getProperty("os.arch")
                + ", "
                + System.getProperty("os.version");
    }

    /**
     * Gets PID of the current JVM.
     *
     * @return PID.
     */
    public static long getPid() {
        // Step 1. Try public ProcessHandle.current().pid(), available since JDK 9.
        // We need to use Reflection here to work well with JDK 8.
        try {
            Class<?> clProcHandle = Class.forName("java.lang.ProcessHandle");
            Method mCurrent = clProcHandle.getMethod("current");
            Method mPid = clProcHandle.getMethod("pid");
            Object objProcHandle = mCurrent.invoke(null);
            Object pid = mPid.invoke(objProcHandle);
            if (pid instanceof Long) {
                return (long) pid;
            }
        } catch (ClassNotFoundException | NoSuchMethodException |
                 AccessControlException | InvocationTargetException |
                 IllegalAccessException e) {
            // Fallthrough.
        }

        RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();

        // Step 2. This is probably JDK 8. Try to call an internal method without
        // going to fallback.
        try {
            Field fJvm = bean.getClass().getDeclaredField("jvm");
            fJvm.setAccessible(true);
            Object objMgmt = fJvm.get(bean);
            Method mPid = objMgmt.getClass().getDeclaredMethod("getProcessId");
            mPid.setAccessible(true);
            Object pid = mPid.invoke(objMgmt);
            if (pid instanceof Integer) {
                return (int) pid;
            }
        } catch (NoSuchMethodException | AccessControlException |
                 InvocationTargetException | IllegalAccessException |
                 NoSuchFieldException e) {
            // Fallthrough.
        }

        // Step 3. Fallback to public API. This potentially resolves hostnames,
        // and thus can be slower than first two steps.
        {
            final String DELIM = "@";
            String name = bean.getName();
            if (name != null) {
                int idx = name.indexOf(DELIM);
                if (idx != -1) {
                    String str = name.substring(0, name.indexOf(DELIM));
                    try {
                        return Long.parseLong(str);
                    } catch (NumberFormatException nfe) {
                        throw new IllegalStateException("Process PID is not a number: " + str);
                    }
                }
            }
            throw new IllegalStateException("Unsupported PID format: " + name);
        }
    }

    /**
     * Gets the PID of the target process.
     * @param process to poll
     * @return PID, or zero if no PID is found
     */
    public static long getPid(Process process) {
        // Step 1. Try Process.pid, available since Java 9.
        try {
            Method m = Process.class.getMethod("pid");
            Object pid = m.invoke(process);
            if (pid instanceof Long) {
                return (long) pid;
            }
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            // Fallthrough
        }

        // Step 2. Try to hack into the JDK 8- UNIXProcess.
        try {
            Class<?> c = Class.forName("java.lang.UNIXProcess");
            Field f = c.getDeclaredField("pid");
            setAccessible(process, f);
            Object o = f.get(process);
            if (o instanceof Integer) {
                return (int) o;
            }
        } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
            // Fallthrough
        }

        // Step 3. Try to hack into JDK 9+ ProcessImpl.
        // Renamed from UNIXProcess with JDK-8071481.
        try {
            Class<?> c = Class.forName("java.lang.ProcessImpl");
            Field f = c.getDeclaredField("pid");
            setAccessible(process, f);
            Object o = f.get(process);
            if (o instanceof Integer) {
                return (int) o;
            }
        } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
            // Fallthrough
        }

        // No dice, return zero
        return 0;
    }

    public static Collection<String> tryWith(String... cmd) {
        Collection<String> messages = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // drain streams, else we might lock up
            InputStreamDrainer errDrainer = new InputStreamDrainer(p.getErrorStream(), baos);
            InputStreamDrainer outDrainer = new InputStreamDrainer(p.getInputStream(), baos);

            errDrainer.start();
            outDrainer.start();

            int err = p.waitFor();

            errDrainer.join();
            outDrainer.join();

            if (err != 0) {
                messages.add(baos.toString());
            }
        } catch (IOException ex) {
            return Collections.singleton(ex.getMessage());
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
        return messages;
    }

    public static Process runAsync(String... cmd) {
        try {
            return new ProcessBuilder(cmd).start();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static Collection<String> runWith(String... cmds) {
        return runWith(Arrays.asList(cmds));
    }

    public static Collection<String> runWith(List<String> cmd) {
        Collection<String> messages = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // drain streams, else we might lock up
            InputStreamDrainer errDrainer = new InputStreamDrainer(p.getErrorStream(), baos);
            InputStreamDrainer outDrainer = new InputStreamDrainer(p.getInputStream(), baos);

            errDrainer.start();
            outDrainer.start();

            int err = p.waitFor();

            errDrainer.join();
            outDrainer.join();

            messages.add(baos.toString());
        } catch (IOException ex) {
            return Collections.singleton(ex.getMessage());
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
        return messages;
    }

    /**
     * We don't access the complete system properties via {@link System#getProperties()} because
     * this would require read/write permissions to the properties. Just copy the properties we
     * want to record in the result.
     *
     * @return Copy of system properties we want to record in the results.
     */
    public static Properties getRecordedSystemProperties() {
        String[] names = new String[]{"java.version", "java.vm.version", "java.vm.name"};
        Properties p = new Properties();
        for (String i : names) {
            p.setProperty(i, System.getProperty(i));
        }
        return p;
    }

    public static Properties readPropertiesFromCommand(List<String> cmd) {
        Properties out = new Properties();
        try {
            File tempFile = FileUtils.tempFile("properties");
            List<String> cmdWithFile = new ArrayList<>(cmd);
            cmdWithFile.add(tempFile.getAbsolutePath());
            Collection<String> errs = tryWith(cmdWithFile.toArray(new String[0]));

            if (!errs.isEmpty()) {
                throw new RuntimeException("Unable to extract forked JVM properties using: '" + join(cmd, " ") + "'; " + errs);
            }
            try (InputStream in = new BufferedInputStream(new FileInputStream(tempFile))) {
                // This will automatically pick UTF-8 based on the encoding in the XML declaration.
                out.loadFromXML(in);
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return out;
    }

    /**
     * Adapts Iterator for Iterable.
     * Can be iterated only once!
     *
     * @param <T> element type
     * @param it iterator
     * @return iterable for given iterator
     */
    public static <T> Iterable<T> adaptForLoop(final Iterator<T> it) {
        return () -> it;
    }

}
