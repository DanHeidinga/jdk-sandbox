/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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

package jdk.javadoc.internal.doclets.toolkit.taglets.snippet.parser;

import java.util.ArrayList;
import java.util.List;

//
// markup-comment = { markup-tag } ;
//     markup-tag = "@" , tag-name , {attribute} [":"] ;
//
// If optional trailing ":" is present, the tag refers to the next line
// rather than to this line.
//
public final class MarkupParser {

    private final static int EOI = 0x1A;
    private char[] buf;
    private int bp;
    private int buflen;
    private char ch;

    public List<Parser.Tag> parse(String input) throws ParseException {

        // No vertical whitespace
        assert input.codePoints().noneMatch(c -> c == '\n' || c == '\r');

        buf = new char[input.length() + 1];
        input.getChars(0, input.length(), buf, 0);
        buf[buf.length - 1] = EOI;
        buflen = buf.length - 1;
        bp = -1;

        nextChar();
        return parse();
    }

    protected List<Parser.Tag> parse() throws ParseException {
        List<Parser.Tag> tags = new ArrayList<>();
        // TODO: what to do with leading and trailing unrecognized markup?
        while (bp < buflen) {
            switch (ch) {
                case '@' -> tags.add(readTag());
                default -> nextChar();
            }
        }

        return tags;
    }

    protected Parser.Tag readTag() throws ParseException {
        nextChar();
        if (!Character.isUnicodeIdentifierStart(ch)) {
            // FIXME: internationalize!
            throw new ParseException("Bad character: '%s' (0x%s)".formatted(ch, Integer.toString(ch, 16)));
        }
        String name = readIdentifier();
        skipWhitespace();

        boolean appliesToNextLine = false;
        List<Attribute> attributes = List.of();

        if (ch == ':') {
            appliesToNextLine = true;
            nextChar();
        } else {
            attributes = attrs();
            skipWhitespace();
            if (ch == ':') {
                appliesToNextLine = true;
                nextChar();
            }
        }

        Parser.Tag i = new Parser.Tag();
        i.name = name;
        i.attributes = attributes;
        i.appliesToNextLine = appliesToNextLine;

        return i;
    }

    protected String readIdentifier() {
        int start = bp;
        nextChar();
        while (bp < buflen && (Character.isUnicodeIdentifierPart(ch) || ch == '-')) {
            nextChar();
        }
        return new String(buf, start, bp - start);
    }

    protected void skipWhitespace() {
        while (bp < buflen && Character.isWhitespace(ch)) {
            nextChar();
        }
    }

    void nextChar() {
        ch = buf[bp < buflen ? ++bp : buflen];
    }

    // Parsing machinery is adapted from com.sun.tools.javac.parser.DocCommentParser:

    private enum ValueKind {
        EMPTY,
        UNQUOTED,
        SINGLE_QUOTED,
        DOUBLE_QUOTED;
    }

    protected List<Attribute> attrs() throws ParseException {
        List<Attribute> attrs = new ArrayList<>();
        skipWhitespace();

        while (bp < buflen && isIdentifierStart(ch)) {
            int nameStartPos = bp;
            String name = readAttributeName();
            skipWhitespace();
            StringBuilder value = new StringBuilder();
            var vkind = ValueKind.EMPTY;
            int valueStartPos = -1;
            if (ch == '=') {
                nextChar();
                skipWhitespace();
                if (ch == '\'' || ch == '"') {
                    vkind = (ch == '\'') ? ValueKind.SINGLE_QUOTED : ValueKind.DOUBLE_QUOTED;
                    char quote = ch;
                    nextChar();
                    valueStartPos = bp;
                    while (bp < buflen && ch != quote) {
                        nextChar();
                    }
                    if (bp >= buflen) { // TODO: unexpected EOL; check for a similar issue in parsing the @snippet tag
                        throw new ParseException("dc.unterminated.string");
                    }
                    addPendingText(value, valueStartPos, bp - 1);
                    nextChar();
                } else {
                    vkind = ValueKind.UNQUOTED;
                    valueStartPos = bp;
                    while (bp < buflen && !isUnquotedAttrValueTerminator(ch)) {
                        nextChar();
                    }
                    // Unlike the case with a quoted value, there's no need to
                    // check for unexpected EOL here; an EOL would simply mean
                    // "end of unquoted value".
                    addPendingText(value, valueStartPos, bp - 1);
                }
                skipWhitespace();
            }

            // material implication:
            //     if vkind != EMPTY then it must be the case that valueStartPos >=0
            assert !(vkind != ValueKind.EMPTY && valueStartPos < 0);

            var attribute = vkind == ValueKind.EMPTY ?
                    new Attribute.Valueless(name, nameStartPos) :
                    new Attribute.Valued(name, value.toString(), nameStartPos, valueStartPos);

            attrs.add(attribute);
        }
        return attrs;
    }

    protected boolean isIdentifierStart(char ch) {
        return Character.isUnicodeIdentifierStart(ch);
    }

    protected String readAttributeName() {
        int start = bp;
        nextChar();
        while (bp < buflen && (Character.isUnicodeIdentifierPart(ch) || ch == '-'))
            nextChar();
        return new String(buf, start, bp - start);
    }

    // Similar to https://html.spec.whatwg.org/multipage/syntax.html#unquoted
    protected boolean isUnquotedAttrValueTerminator(char ch) {
        switch (ch) {
            case ':': // indicates that the instruction relates to the next line
            case ' ': case '\t':
            case '"': case '\'': case '`':
            case '=': case '<': case '>':
                return true;
            default:
                return false;
        }
    }

    protected void addPendingText(StringBuilder b, int textStart, int textEnd) {
        if (textStart != -1) {
            if (textStart <= textEnd) {
                b.append(buf, textStart, (textEnd - textStart) + 1);
            }
        }
    }
}
