package br.com.ads;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 *
 * @author andersonluizpereira
 * Java Agent para medir latência de métodos em tempo de execução.
 * Suporte a modo VERBOSE e exportação em JSON.
 * Compatível com Java 8–21.
 */
public class LatencyAgent {

    public static class Config {
        public final String[] includes;
        public final String[] excludes;
        public final long minNanos;
        public final int top;
        public final boolean verbose;
        public final String outputFile;

        public Config(String agentArgs) {
            String inc = null, exc = null, out = "latency-metrics.json";
            long minMicros = 0;
            int t = 10;
            boolean v = false;

            if (agentArgs != null) {
                for (String kv : agentArgs.split(";")) {
                    String[] p = kv.split("=", 2);
                    if (p.length != 2) continue;
                    String key = p[0].trim();
                    String val = p[1].trim();
                    switch (key.toLowerCase()) {
                        case "include": inc = val; break;
                        case "exclude": exc = val; break;
                        case "minmicros":
                            try { minMicros = Long.parseLong(val); } catch (NumberFormatException ignored) {}
                            break;
                        case "top":
                            try { t = Integer.parseInt(val); } catch (NumberFormatException ignored) {}
                            break;
                        case "verbose": v = Boolean.parseBoolean(val); break;
                        case "output": out = val; break;
                    }
                }
            }

            this.includes = (inc == null || inc.trim().isEmpty()) ? new String[0] : inc.split(",");
            this.excludes = (exc == null || exc.trim().isEmpty()) ? new String[0] : exc.split(",");
            this.minNanos = minMicros * 1_000L;
            this.top = t;
            this.verbose = v;
            this.outputFile = out;
        }
    }

    public static class Stats {
        public static class Item {
            public final LongAdder calls = new LongAdder();
            public final LongAdder totalNanos = new LongAdder();
            public volatile long maxNanos = 0L;
        }

        private static final ConcurrentHashMap<String, Item> DATA = new ConcurrentHashMap<>();
        private static volatile long minRecordNanos = 0L;
        private static volatile int topN = 10;
        private static volatile String outputFile = "latency-metrics.json";

        public static void configure(long minNanos, int top, String file) {
            minRecordNanos = minNanos;
            topN = top;
            outputFile = file;
        }

        public static void record(String method, long nanos) {
            if (nanos < minRecordNanos) return;
            Item it = DATA.computeIfAbsent(method, k -> new Item());
            it.calls.increment();
            it.totalNanos.add(nanos);
            if (nanos > it.maxNanos) it.maxNanos = nanos;
        }

        public static void dump() {
            System.err.println("\n=== MÉTODOS MAIS LENTOS (por tempo total acumulado) ===");
            System.err.printf("%-8s  %-8s  %-12s  %-12s  %s%n",
                    "calls", "avg(ms)", "total(ms)", "max(ms)", "method");

            List<Map<String, Object>> jsonList = new ArrayList<>();

            DATA.entrySet().stream()
                    .sorted(Comparator.comparingLong(
                            (Map.Entry<String, Item> e) -> e.getValue().totalNanos.sum()).reversed())
                    .limit(topN)
                    .forEach(e -> {
                        Item it = e.getValue();
                        long calls = it.calls.sum();
                        double avgMs = (it.totalNanos.sum() / 1_000_000.0) / Math.max(1, calls);
                        double totalMs = it.totalNanos.sum() / 1_000_000.0;
                        double maxMs = it.maxNanos / 1_000_000.0;

                        System.err.printf("%-8d  %-8.3f  %-12.3f  %-12.3f  %s%n",
                                calls, avgMs, totalMs, maxMs, e.getKey());

                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("method", e.getKey());
                        entry.put("calls", calls);
                        entry.put("avg_ms", avgMs);
                        entry.put("total_ms", totalMs);
                        entry.put("max_ms", maxMs);
                        jsonList.add(entry);
                    });

            System.err.println("========================================================\n");

            // 🧾 Salvar JSON
            try (FileWriter fw = new FileWriter(outputFile)) {
                fw.write(toJson(jsonList));
                System.err.println("[LatencyAgent] Exportado JSON: " + outputFile);
            } catch (IOException e) {
                System.err.println("[LatencyAgent] Falha ao exportar JSON: " + e.getMessage());
            }
        }

        private static String toJson(List<Map<String, Object>> list) {
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                Map<String, Object> map = list.get(i);
                sb.append("  {");
                int j = 0;
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    sb.append("\"").append(entry.getKey()).append("\": ");
                    Object val = entry.getValue();
                    if (val instanceof Number) sb.append(val);
                    else sb.append("\"").append(val).append("\"");
                    if (++j < map.size()) sb.append(", ");
                }
                sb.append("}");
                if (i < list.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]\n");
            return sb.toString();
        }
    }

    public static class TimingAdvice {
        @Advice.OnMethodEnter
        public static long onEnter() {
            return System.nanoTime();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Origin String method,
                                  @Advice.Enter long startTime) {
            long dur = System.nanoTime() - startTime;
            Stats.record(method, dur);
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        Config cfg = new Config(agentArgs);
        Stats.configure(cfg.minNanos, cfg.top, cfg.outputFile);

        if (cfg.verbose) {
            System.err.println("[LatencyAgent] Modo VERBOSE ativo — mostrando instrumentações detalhadas");
        }

        ElementMatcher.Junction<TypeDescription> typeMatcher = ElementMatchers.any();
        if (cfg.includes.length > 0) {
            ElementMatcher.Junction<TypeDescription> includeMatcher = ElementMatchers.none();
            for (String include : cfg.includes) {
                includeMatcher = includeMatcher.or(ElementMatchers.nameStartsWith(include));
            }
            typeMatcher = typeMatcher.and(includeMatcher);
        }
        if (cfg.excludes.length > 0) {
            for (String exclude : cfg.excludes) {
                typeMatcher = typeMatcher.and(ElementMatchers.not(ElementMatchers.nameStartsWith(exclude)));
            }
        }

        new AgentBuilder.Default()
                .ignore(ElementMatchers.nameStartsWith("java.")
                        .or(ElementMatchers.nameStartsWith("javax."))
                        .or(ElementMatchers.nameStartsWith("sun."))
                        .or(ElementMatchers.nameStartsWith("jdk."))
                        .or(ElementMatchers.nameStartsWith("com.sun."))
                        .or(ElementMatchers.nameStartsWith("org.graalvm."))
                        .or(ElementMatchers.nameStartsWith("net.bytebuddy.")))
                .type(typeMatcher)
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    if (cfg.verbose) {
                        System.err.println("[LatencyAgent] Instrumentando: " + typeDescription.getName());
                    }
                    return builder.method(ElementMatchers.isMethod()
                                    .and(ElementMatchers.not(ElementMatchers.isConstructor()))
                                    .and(ElementMatchers.not(ElementMatchers.isAbstract())))
                            .intercept(Advice.to(TimingAdvice.class));
                })
                .with(new AgentBuilder.Listener() {
                    @Override public void onDiscovery(String typeName, ClassLoader cl, JavaModule m, boolean loaded) {}
                    @Override public void onTransformation(TypeDescription td, ClassLoader cl, JavaModule m, boolean loaded, DynamicType dt) {
                        if (cfg.verbose) System.err.println("[LatencyAgent] Transformado: " + td.getName());
                    }
                    @Override public void onIgnored(TypeDescription td, ClassLoader cl, JavaModule m, boolean loaded) {}
                    @Override public void onError(String typeName, ClassLoader cl, JavaModule m, boolean loaded, Throwable t) {
                        System.err.println("[LatencyAgent] Erro ao instrumentar " + typeName + ": " + t);
                    }
                    @Override public void onComplete(String typeName, ClassLoader cl, JavaModule m, boolean loaded) {}
                })
                .installOn(inst);

        Runtime.getRuntime().addShutdownHook(new Thread(Stats::dump, "latency-agent-dump"));

        System.err.println("[LatencyAgent] Ativo. min=" + Duration.ofNanos(cfg.minNanos) +
                ", top=" + cfg.top + ", verbose=" + cfg.verbose);
        System.err.println("[LatencyAgent] JVM: " + System.getProperty("java.version"));
    }
}
