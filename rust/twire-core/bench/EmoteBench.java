import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.perflyst.twire.chat.NativeChat;

/**
 * Head-to-head benchmark of the Rust chat core against the exact Kotlin logic it
 * replaces in Twire V2, on the same JVM, with the same inputs.
 *
 * The baseline is a line-by-line Java port of:
 *   - ChatMessage.getEmotesFromMessage  (message.split(" ".toRegex()) loop)
 *   - ChatEmoteManager.findTwitchEmotes (regex + split(",".toRegex()))
 *   - ChatFragment autocomplete         (filter { contains } .sortedBy { indexOf } .take(10))
 * including the per-call `Regex` construction those Kotlin call sites perform.
 *
 * It also checks the native output is identical to the Kotlin-equivalent output.
 *
 * Usage: java -cp out -Djava.library.path=target/release EmoteBench [libtwire_core.so]
 */
public final class EmoteBench {
    static final int EMOTE_COUNT = 10_000;
    static final int MESSAGE_COUNT = 20_000;
    static final int WARMUP = 12;
    static final int ROUNDS = 40;
    static final String[] QUERIES = {"ka", "lol", "pe", "emote1", "zzz", "a"};

    public static void main(String[] args) {
        NativeChat.loadForHost(args.length > 0 ? args[0] : null);

        String[] keywords = buildKeywords();
        String[] messages = buildMessages(keywords);
        String[] tags = buildTags();

        EmoteIndex baseline = new EmoteIndex(keywords);
        long handle = NativeChat.nativeNewEmoteIndex(keywords);
        if (handle == 0) {
            throw new IllegalStateException("native index creation failed");
        }

        verifyParity(baseline, handle, keywords, messages, tags);

        bench("emote word scan (per message)", messages.length, () -> {
            long sink = 0;
            for (String m : messages) sink += baseline.getEmotesFromMessage(m).size();
            return sink;
        }, () -> {
            long sink = 0;
            for (String m : messages) sink += NativeChat.nativeScanEmotes(handle, m).length;
            return sink;
        });

        bench("irc emote tag parse (per tag)", tags.length, () -> {
            long sink = 0;
            for (String t : tags) sink += baseline.findTwitchEmotes(t).size();
            return sink;
        }, () -> {
            long sink = 0;
            for (String t : tags) sink += NativeChat.nativeParseEmoteTag(t).length;
            return sink;
        });

        bench("autocomplete search (per query, 10k emotes)", QUERIES.length, () -> {
            long sink = 0;
            for (String q : QUERIES) sink += baseline.autocomplete(q, 10).size();
            return sink;
        }, () -> {
            long sink = 0;
            for (String q : QUERIES) sink += NativeChat.nativeSearchEmotes(handle, q, 10).length;
            return sink;
        });

        NativeChat.nativeFreeEmoteIndex(handle);
    }

    // ---------------------------------------------------------------- baseline

    static final class EmoteIndex {
        final Map<String, String> keywordToEmote = new HashMap<>();
        final List<String> keywords;

        EmoteIndex(String[] keywords) {
            this.keywords = Arrays.asList(keywords);
            for (String k : keywords) keywordToEmote.put(k, k);
        }

        /** ChatMessage.getEmotesFromMessage */
        Map<Integer, String> getEmotesFromMessage(String message) {
            int position = 0;
            Map<Integer, String> found = new HashMap<>();
            String[] words = Pattern.compile(" ").split(message);
            for (String word : words) {
                String emote = keywordToEmote.get(word);
                if (emote != null) found.put(position, emote);
                position += word.length() + 1;
            }
            return found;
        }

        static final Pattern EMOTE_PATTERN = Pattern.compile("(\\w+):((?:\\d+-\\d+,?)+)");

        /** ChatEmoteManager.findTwitchEmotes */
        Map<Integer, String> findTwitchEmotes(String line) {
            Map<Integer, String> emotes = new HashMap<>();
            Matcher matcher = EMOTE_PATTERN.matcher(line);
            while (matcher.find()) {
                String id = matcher.group(1);
                String[] positions = Pattern.compile(",").split(matcher.group(2));
                int[] starts = new int[positions.length];
                for (int i = 0; i < positions.length; i++) {
                    starts[i] = Integer.parseInt(Pattern.compile("-").split(positions[i])[0]);
                }
                for (int start : starts) emotes.put(start, id);
            }
            return emotes;
        }

        /** ChatFragment autocomplete */
        List<String> autocomplete(String lowerQuery, int limit) {
            List<String> out = new ArrayList<>();
            for (String keyword : keywords) {
                if (keyword.toLowerCase().contains(lowerQuery)) out.add(keyword);
            }
            out.sort((a, b) -> Integer.compare(
                    a.toLowerCase().indexOf(lowerQuery),
                    b.toLowerCase().indexOf(lowerQuery)));
            return out.subList(0, Math.min(limit, out.size()));
        }
    }

    // ------------------------------------------------------------------ parity

    static void verifyParity(EmoteIndex baseline, long handle, String[] keywords,
                             String[] messages, String[] tags) {
        for (String message : messages) {
            Map<Integer, String> expected = baseline.getEmotesFromMessage(message);
            int[] packed = NativeChat.nativeScanEmotes(handle, message);
            if (packed.length != expected.size() * 3) {
                throw new AssertionError("scan size mismatch for: " + message);
            }
            for (int i = 0; i < packed.length; i += 3) {
                String expectedKeyword = expected.get(packed[i]);
                String actual = keywords[packed[i + 2]];
                if (!actual.equals(expectedKeyword)) {
                    throw new AssertionError("scan keyword mismatch for: " + message);
                }
                if (actual.length() != packed[i + 1]) {
                    throw new AssertionError("scan length mismatch for: " + message);
                }
            }
        }

        for (String tag : tags) {
            Map<Integer, String> expected = baseline.findTwitchEmotes(tag);
            int[] packed = NativeChat.nativeParseEmoteTag(tag);
            int seen = 0;
            int cursor = 0;
            while (cursor < packed.length) {
                int idStart = packed[cursor];
                int idLen = packed[cursor + 1];
                int rangeCount = packed[cursor + 2];
                cursor += 3;
                String id = tag.substring(idStart, idStart + idLen);
                for (int r = 0; r < rangeCount; r++) {
                    int start = packed[cursor];
                    cursor += 2;
                    if (!id.equals(expected.get(start))) {
                        throw new AssertionError("irc tag mismatch for: " + tag);
                    }
                    seen++;
                }
            }
            if (seen != expected.size()) {
                throw new AssertionError("irc tag group mismatch for: " + tag);
            }
        }

        for (String q : new String[]{"ka", "lol", "pe", "emote1", "zzz", "a", "kappa"}) {
            List<String> expected = baseline.autocomplete(q, 10);
            int[] actual = NativeChat.nativeSearchEmotes(handle, q, 10);
            if (actual.length != expected.size()) {
                throw new AssertionError("autocomplete size mismatch for " + q);
            }
            for (int i = 0; i < actual.length; i++) {
                if (!keywords[actual[i]].equals(expected.get(i))) {
                    throw new AssertionError("autocomplete order mismatch for " + q
                            + ": expected " + expected.get(i) + " got " + keywords[actual[i]]);
                }
            }
        }
        System.out.println("parity: OK (" + messages.length + " messages, "
                + tags.length + " emote tags, " + keywords.length + " emotes)");
    }

    // --------------------------------------------------------------- measuring

    interface Work {
        long run();
    }

    static final class Result {
        double nanosPerOp;
        double bytesPerOp;
    }

    static void bench(String label, int ops, Work baseline, Work nativeImpl) {
        for (int i = 0; i < WARMUP; i++) {
            baseline.run();
            nativeImpl.run();
        }

        Result b = measure(baseline, ops);
        Result n = measure(nativeImpl, ops);

        System.out.printf("%n%s%n", label);
        System.out.printf("  kotlin-equivalent : %9.2f us/op %12.0f B allocated/op%n",
                b.nanosPerOp / 1000.0, b.bytesPerOp);
        System.out.printf("  rust core (jni)   : %9.2f us/op %12.0f B allocated/op%n",
                n.nanosPerOp / 1000.0, n.bytesPerOp);
        System.out.printf("  speedup           : %9.2fx      allocation cut: %5.1f%%%n",
                b.nanosPerOp / n.nanosPerOp, 100.0 * (1 - n.bytesPerOp / b.bytesPerOp));
    }

    static final com.sun.management.ThreadMXBean THREAD_BEAN =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    static Result measure(Work work, int ops) {
        long id = Thread.currentThread().getId();
        THREAD_BEAN.getThreadAllocatedBytes(id); // prime the counter

        long bestNanos = Long.MAX_VALUE;
        long bytes = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long beforeBytes = THREAD_BEAN.getThreadAllocatedBytes(id);
            long start = System.nanoTime();
            long sink = work.run();
            long elapsed = System.nanoTime() - start;
            bytes += THREAD_BEAN.getThreadAllocatedBytes(id) - beforeBytes;
            if (sink == 42) System.out.print("");
            bestNanos = Math.min(bestNanos, elapsed);
        }

        Result result = new Result();
        result.nanosPerOp = bestNanos / (double) ops;
        result.bytesPerOp = bytes / (double) ROUNDS / ops;
        return result;
    }

    // ------------------------------------------------------------------- data

    static String[] buildKeywords() {
        Random random = new Random(7);
        String[] base = {"Kappa", "LUL", "PogChamp", "KEKW", "PepeHands", "monkaS",
                "PogU", "catJAM", "pepeDance", "widepeepoHappy"};
        String[] out = new String[EMOTE_COUNT];
        for (int i = 0; i < out.length; i++) {
            if (i < base.length * 20) {
                out[i] = base[i % base.length] + (i / base.length);
            } else {
                StringBuilder sb = new StringBuilder();
                int len = 4 + random.nextInt(9);
                for (int c = 0; c < len; c++) {
                    sb.append((char) ('a' + random.nextInt(26)));
                }
                out[i] = sb.append(i).toString();
            }
        }
        return out;
    }

    static String[] buildMessages(String[] keywords) {
        Random random = new Random(11);
        String[] out = new String[MESSAGE_COUNT];
        String[] filler = {"nice", "that", "was", "so", "funny", "lol", "gg", "stream", "today",
                "the", "chat", "is", "pog", "and", "then", "he", "died", "again"};
        for (int i = 0; i < out.length; i++) {
            StringBuilder sb = new StringBuilder();
            int words = 6 + random.nextInt(14);
            for (int w = 0; w < words; w++) {
                if (w > 0) sb.append(' ');
                if (random.nextInt(4) == 0) {
                    sb.append(keywords[random.nextInt(200)]);
                } else {
                    sb.append(filler[random.nextInt(filler.length)]);
                }
            }
            out[i] = sb.toString();
        }
        return out;
    }

    static String[] buildTags() {
        Random random = new Random(13);
        String[] out = new String[2000];
        for (int i = 0; i < out.length; i++) {
            StringBuilder sb = new StringBuilder();
            int groups = 1 + random.nextInt(4);
            int offset = 0;
            for (int g = 0; g < groups; g++) {
                if (g > 0) sb.append('/');
                sb.append(25 + random.nextInt(1000)).append(':');
                int ranges = 1 + random.nextInt(3);
                for (int r = 0; r < ranges; r++) {
                    if (r > 0) sb.append(',');
                    int start = offset;
                    int end = start + 3 + random.nextInt(6);
                    sb.append(start).append('-').append(end);
                    offset = end + 2;
                }
            }
            out[i] = sb.toString();
        }
        return out;
    }
}
