/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.parser;

import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Node;
import com.facebook.presto.sql.tree.PathSpecification;
import com.facebook.presto.sql.tree.Statement;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.Pair;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.TerminalNode;

import javax.inject.Inject;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class SqlParser
{
    private static final BaseErrorListener LEXER_ERROR_LISTENER = new BaseErrorListener()
    {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String message, RecognitionException e)
        {
            throw new ParsingException(message, e, line, charPositionInLine);
        }
    };

    private static final ErrorHandler PARSER_ERROR_HANDLER = ErrorHandler.builder()
            .specialRule(SqlBaseParser.RULE_expression, "<expression>")
            .specialRule(SqlBaseParser.RULE_booleanExpression, "<expression>")
            .specialRule(SqlBaseParser.RULE_valueExpression, "<expression>")
            .specialRule(SqlBaseParser.RULE_primaryExpression, "<expression>")
            .specialRule(SqlBaseParser.RULE_identifier, "<identifier>")
            .specialRule(SqlBaseParser.RULE_string, "<string>")
            .specialRule(SqlBaseParser.RULE_query, "<query>")
            .specialRule(SqlBaseParser.RULE_type, "<type>")
            .specialToken(SqlBaseLexer.INTEGER_VALUE, "<integer>")
            .ignoredRule(SqlBaseParser.RULE_nonReserved)
            .build();

    private final EnumSet<IdentifierSymbol> allowedIdentifierSymbols;
    private boolean enhancedErrorHandlerEnabled;

    public SqlParser()
    {
        this(new SqlParserOptions());
    }

    @Inject
    public SqlParser(SqlParserOptions options)
    {
        requireNonNull(options, "options is null");
        allowedIdentifierSymbols = EnumSet.copyOf(options.getAllowedIdentifierSymbols());
        enhancedErrorHandlerEnabled = options.isEnhancedErrorHandlerEnabled();
    }

    /**
     * Consider using {@link #createStatement(String, ParsingOptions)}
     */
    @Deprecated
    public Statement createStatement(String sql)
    {
        return createStatement(sql, new ParsingOptions());
    }

    public Statement createStatement(String sql, ParsingOptions parsingOptions)
    {
        return (Statement) invokeParser("statement", sql, SqlBaseParser::singleStatement, parsingOptions);
    }

    /**
     * Consider using {@link #createExpression(String, ParsingOptions)}
     */
    @Deprecated
    public Expression createExpression(String expression)
    {
        return createExpression(expression, new ParsingOptions());
    }

    public Expression createExpression(String expression, ParsingOptions parsingOptions)
    {
        return (Expression) invokeParser("expression", expression, SqlBaseParser::standaloneExpression, parsingOptions);
    }

    public PathSpecification createPathSpecification(String expression)
    {
        return (PathSpecification) invokeParser("path specification", expression, SqlBaseParser::standalonePathSpecification, new ParsingOptions());
    }

    private Node invokeParser(String name, String sql, Function<SqlBaseParser, ParserRuleContext> parseFunction, ParsingOptions parsingOptions)
    {
        try {
            /**
             * CaseInsensitiveStream类 其是为了使输入的SQL语句大小写不敏感,即忽略SQL语句关键字的大小写并视为
             等效的关键字，例如将Select、select 与SELECT视为等效关键字。
             */
            SqlBaseLexer lexer = new SqlBaseLexer(new CaseInsensitiveStream(CharStreams.fromString(sql)));
            CommonTokenStream tokenStream = new CommonTokenStream(lexer);
            SqlBaseParser parser = new SqlBaseParser(tokenStream);

            // Override the default error strategy to not attempt inserting or deleting a token.
            // Otherwise, it messes up error reporting
            parser.setErrorHandler(new DefaultErrorStrategy()
            {
                @Override
                public Token recoverInline(Parser recognizer)
                        throws RecognitionException
                {
                    if (nextTokensContext == null) {
                        throw new InputMismatchException(recognizer);
                    }
                    else {
                        throw new InputMismatchException(recognizer, nextTokensState, nextTokensContext);
                    }
                }
            });

            /**
             * parser.addParseListener(new PostProcessor()
             这是为了做- -些解析时的处理，包括- -些异常处理。
             */
            parser.addParseListener(new PostProcessor(Arrays.asList(parser.getRuleNames()), parsingOptions.getWarningConsumer()));

            /**
             * lexer和parser的removeErrorListeners和addErrorListener 是重写错误发生时的处理,
             即抛出ParsingException, ParsingException继承自RuntimeException,以上PostProcessor
             处理函数中抛出的也是ParsingException。
             */
            lexer.removeErrorListeners();
            lexer.addErrorListener(LEXER_ERROR_LISTENER);

            parser.removeErrorListeners();

            if (enhancedErrorHandlerEnabled) {
                parser.addErrorListener(PARSER_ERROR_HANDLER);
            }
            else {
                parser.addErrorListener(LEXER_ERROR_LISTENER);
            }

            ParserRuleContext tree;
            try {
                // first, try parsing with potentially faster SLL mode
                /**
                 * tree = parseFunction.apply(parser)
                 首先尝试使用速度更快的SLL模式进行语法预测，但是该模式不能保证解析时对语
                 法错误的SQL正确处理，即有可能抛出异常。
                 */
                parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
                tree = parseFunction.apply(parser);
            } catch (ParseCancellationException ex) {
                // if we fail, parse with LL mode
                tokenStream.reset(); // rewind input stream
                parser.reset();

                /**
                 * tree = parseFunction.apply(parser)
                 抛出异常时使用LL模式进行语法预测，该模式能够确保对SQL的解析结果是正确
                 */
                parser.getInterpreter().setPredictionMode(PredictionMode.LL);
                tree = parseFunction.apply(parser);
            }

            return new AstBuilder(parsingOptions).visit(tree);
        }
        catch (StackOverflowError e) {
            throw new ParsingException(name + " is too large (stack overflow while parsing)");
        }
    }

    private class PostProcessor
            extends SqlBaseBaseListener
    {
        private final List<String> ruleNames;
        private final Consumer<ParsingWarning> warningConsumer;

        public PostProcessor(List<String> ruleNames, Consumer<ParsingWarning> warningConsumer)
        {
            this.ruleNames = ruleNames;
            this.warningConsumer = requireNonNull(warningConsumer, "warningConsumer is null");
        }

        /**
         * exitUnquotedIdentifier如果未用引号括起来的标识符中有“@”或“:”等符号则抛出异常。
         * @param context
         */
        @Override
        public void exitUnquotedIdentifier(SqlBaseParser.UnquotedIdentifierContext context)
        {
            String identifier = context.IDENTIFIER().getText();
            for (IdentifierSymbol identifierSymbol : EnumSet.complementOf(allowedIdentifierSymbols)) {
                char symbol = identifierSymbol.getSymbol();
                if (identifier.indexOf(symbol) >= 0) {
                    throw new ParsingException("identifiers must not contain '" + identifierSymbol.getSymbol() + "'", null, context.IDENTIFIER().getSymbol().getLine(), context.IDENTIFIER().getSymbol().getCharPositionInLine());
                }
            }
        }

        /**
         * 如果标识符是用反引号"’"括起来的则抛出异常。
         * @param context
         */
        @Override
        public void exitBackQuotedIdentifier(SqlBaseParser.BackQuotedIdentifierContext context)
        {
            Token token = context.BACKQUOTED_IDENTIFIER().getSymbol();
            throw new ParsingException(
                    "backquoted identifiers are not supported; use double quotes to quote identifiers",
                    null,
                    token.getLine(),
                    token.getCharPositionInLine());
        }

        /**
         * 如果标识符是以数字开头的则抛出异常。
         * @param context
         */
        @Override
        public void exitDigitIdentifier(SqlBaseParser.DigitIdentifierContext context)
        {
            Token token = context.DIGIT_IDENTIFIER().getSymbol();
            throw new ParsingException(
                    "identifiers must not start with a digit; surround the identifier with double quotes",
                    null,
                    token.getLine(),
                    token.getCharPositionInLine());
        }

        /**
         * 将非保留关键字替换成标识符，以使处理时将其当作标识符，例如SELECT
         show .这里将show当作-一个标识符，而不是当作关键字进行处理。

         * @param context
         */
        @Override
        public void exitNonReserved(SqlBaseParser.NonReservedContext context)
        {
            // we can't modify the tree during rule enter/exit event handling unless we're dealing with a terminal.
            // Otherwise, ANTLR gets confused an fires spurious notifications.
            if (!(context.getChild(0) instanceof TerminalNode)) {
                int rule = ((ParserRuleContext) context.getChild(0)).getRuleIndex();
                throw new AssertionError("nonReserved can only contain tokens. Found nested rule: " + ruleNames.get(rule));
            }

            // replace nonReserved words with IDENT tokens
            context.getParent().removeLastChild();

            Token token = (Token) context.getChild(0).getPayload();
            if (token.getText().equalsIgnoreCase("CURRENT_ROLE")) {
                warningConsumer.accept(new ParsingWarning(format("Reserved word used: %s", token.getText()), token.getLine(), token.getCharPositionInLine()));
            }
            context.getParent().addChild(new CommonToken(
                    new Pair<>(token.getTokenSource(), token.getInputStream()),
                    SqlBaseLexer.IDENTIFIER,
                    token.getChannel(),
                    token.getStartIndex(),
                    token.getStopIndex()));
        }
    }
}
