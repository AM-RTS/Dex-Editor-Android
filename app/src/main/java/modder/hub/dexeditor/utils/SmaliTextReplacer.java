package modder.hub.dexeditor.utils;

import com.android.tools.smali.smali.smaliParser;
import com.android.tools.smali.smali2.SmaliCatchErrFlexLexer;
import com.android.tools.smali.util.StringUtils;

import org.antlr.runtime.CommonToken;
import org.antlr.runtime.Token;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Shared literal/regex replacement for editor and patch-file operations. */
public final class SmaliTextReplacer {
    private SmaliTextReplacer() {}

    public static Result transformStringLiterals(String text, UnaryOperator<String> transform) {
        return replaceStringLiteralTokens(text, literal -> {
            String decoded = decodeStringLiteralContent(literal);
            String replaced = transform.apply(decoded);
            return new Result(replaced == null ? null : StringUtils.escapeString(replaced),
                    replaced == null || replaced.equals(decoded) ? 0 : 1);
        });
    }

    /** Decodes Smali string escapes so matching uses the same values as DEX strings. */
    public static String decodeStringLiteralContent(String literal) {
        StringBuilder decoded = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char current = literal.charAt(i);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            if (++i == literal.length()) throw new IllegalArgumentException("Incomplete Smali string escape.");
            char escaped = literal.charAt(i);
            switch (escaped) {
                case 'n': decoded.append('\n'); break;
                case 'r': decoded.append('\r'); break;
                case 't': decoded.append('\t'); break;
                case 'b': decoded.append('\b'); break;
                case 'f': decoded.append('\f'); break;
                case '\\': decoded.append('\\'); break;
                case '"': decoded.append('"'); break;
                case '\'': decoded.append('\''); break;
                case '0': case '1': case '2': case '3': case '4': case '5': case '6': case '7':
                    int octal = escaped - '0';
                    int maxOctalDigits = escaped <= '3' ? 3 : 2;
                    for (int digit = 1; digit < maxOctalDigits && i + 1 < literal.length(); digit++) {
                        char next = literal.charAt(i + 1);
                        if (next < '0' || next > '7') break;
                        octal = (octal << 3) | (next - '0');
                        i++;
                    }
                    decoded.append((char) octal);
                    break;
                case 'u':
                    if (i + 4 >= literal.length()) throw new IllegalArgumentException("Incomplete Smali Unicode escape.");
                    int value = 0;
                    for (int digit = 1; digit <= 4; digit++) {
                        int hex = Character.digit(literal.charAt(i + digit), 16);
                        if (hex < 0) throw new IllegalArgumentException("Invalid Smali Unicode escape.");
                        value = (value << 4) | hex;
                    }
                    decoded.append((char) value);
                    i += 4;
                    break;
                default: throw new IllegalArgumentException("Unsupported Smali string escape: \\" + escaped);
            }
        }
        return decoded.toString();
    }

    private static Result replaceStringLiteralTokens(String text, Function<String, Result> transform) {
        if (text == null || text.isEmpty()) return new Result(text, 0);
        SmaliCatchErrFlexLexer lexer = new SmaliCatchErrFlexLexer(new StringReader(text), Integer.MAX_VALUE);
        StringBuilder output = new StringBuilder(text.length());
        int copiedThrough = 0;
        int matches = 0;
        Token token;
        while ((token = lexer.nextToken()).getType() != Token.EOF) {
            if (token.getType() != smaliParser.STRING_LITERAL) continue;
            CommonToken stringToken = (CommonToken) token;
            int start = stringToken.getStartIndex();
            int end = stringToken.getStopIndex() + 1;
            if (start < copiedThrough || end > text.length() || end - start < 2) {
                throw new IllegalArgumentException("Could not determine Smali string literal boundaries.");
            }
            String literal = text.substring(start + 1, end - 1);
            Result replacement = transform.apply(literal);
            if (replacement == null || replacement.matches == 0) continue;
            output.append(text, copiedThrough, start).append('"').append(replacement.text).append('"');
            copiedThrough = end;
            matches += replacement.matches;
        }
        if (!lexer.getErrors().isEmpty()) {
            throw new IllegalArgumentException("Could not scan Smali string literals: "
                    + lexer.getErrorsString().trim());
        }
        if (matches == 0) return new Result(text, 0);
        output.append(text, copiedThrough, text.length());
        return new Result(output.toString(), matches);
    }

    public static SearchMatcher compileSearchMatcher(String find, boolean regex, boolean matchCase,
                                                     boolean exactlyMatch) {
        if (find == null) throw new IllegalArgumentException("Search text cannot be null.");
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE;
        return new SearchMatcher(Pattern.compile(regex ? find : Pattern.quote(find), flags), find,
                regex, matchCase, exactlyMatch);
    }

    public static final class SearchMatcher {
        private final Pattern pattern;
        private final String find;
        private final boolean regex;
        private final boolean matchCase;
        private final boolean exactlyMatch;

        private SearchMatcher(Pattern pattern, String find, boolean regex, boolean matchCase,
                              boolean exactlyMatch) {
            this.pattern = pattern;
            this.find = find;
            this.regex = regex;
            this.matchCase = matchCase;
            this.exactlyMatch = exactlyMatch;
        }

        public boolean matches(String text) {
            if (text == null) return false;
            if (regex) return pattern.matcher(text).find();
            if (exactlyMatch) return matchCase ? text.equals(find) : text.equalsIgnoreCase(find);
            if (matchCase) return text.contains(find);
            for (int i = 0; i <= text.length() - find.length(); i++) {
                if (text.regionMatches(true, i, find, 0, find.length())) return true;
            }
            return false;
        }
    }

    public static Rule compile(String find, String replacement, boolean regex, boolean matchCase, boolean stringsOnly) {
        return compile(find, replacement, regex, matchCase, stringsOnly, false);
    }

    public static Rule compile(String find, String replacement, boolean regex, boolean matchCase,
                               boolean stringsOnly, boolean exactlyMatch) {
        if (find == null || find.isEmpty()) throw new IllegalArgumentException("Find text cannot be empty.");
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE;
        String expression = regex ? find : Pattern.quote(find);
        if (exactlyMatch) expression = "^(?:" + expression + ")$";
        Pattern pattern = Pattern.compile(expression, flags);
        return new Rule(pattern, replacement == null ? "" : replacement, regex, stringsOnly);
    }

    public static final class Rule {
        private final Pattern pattern;
        private final String replacement;
        private final boolean regex;
        private final boolean stringsOnly;

        private Rule(Pattern pattern, String replacement, boolean regex, boolean stringsOnly) {
            this.pattern = pattern;
            this.replacement = replacement;
            this.regex = regex;
            this.stringsOnly = stringsOnly;
        }

        public Result apply(String text) {
            if (text == null || text.isEmpty()) return new Result(text, 0);
            return stringsOnly ? replaceStringLiterals(text) : replaceText(text);
        }

        private Result replaceText(String text) {
            Matcher matcher = pattern.matcher(text);
            StringBuffer output = new StringBuffer(text.length());
            int count = 0;
            while (matcher.find()) {
                rejectEmptyMatch(matcher);
                matcher.appendReplacement(output, regex ? replacement : Matcher.quoteReplacement(replacement));
                count++;
            }
            if (count == 0) return new Result(text, 0);
            matcher.appendTail(output);
            return new Result(output.toString(), count);
        }

        private Result replaceStringLiterals(String text) {
            return replaceStringLiteralTokens(text, literal -> {
                String decoded = decodeStringLiteralContent(literal);
                Result replacement = replaceText(decoded);
                return replacement.matches == 0 ? replacement
                        : new Result(StringUtils.escapeString(replacement.text), replacement.matches);
            });
        }

        private void rejectEmptyMatch(Matcher matcher) {
            if (matcher.start() == matcher.end()) {
                throw new IllegalArgumentException("Regex matches an empty string; zero-width patch matches are not allowed.");
            }
        }
    }

    public static final class Result {
        private final String text;
        private final int matches;

        private Result(String text, int matches) {
            this.text = text;
            this.matches = matches;
        }

        public String getText() { return text; }
        public int getMatches() { return matches; }
    }
}
