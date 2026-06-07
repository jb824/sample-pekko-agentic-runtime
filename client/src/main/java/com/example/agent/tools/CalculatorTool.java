package com.example.agent.tools;

import com.example.agent.api.Tool;
import com.example.agent.api.ToolParam;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public class CalculatorTool {

    private static final Pattern DECIMAL =
            Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");

    public record CalculatorResult(
            String result,
            String display,
            String precision,
            List<String> warnings
    ) {}

    @Tool(description = "Calculator for basic arithmetic operations geared towards accounting")
    public CalculatorResult calculate(
            @ToolParam(
                    name = "operation",
                    description = "ADD, SUBTRACT, MULTIPLY, DIVIDE, PERCENT, PERCENT_CHANGE"
            )
            String operation,
            @ToolParam(
                    name = "amounts",
                    description = "Decimal numbers as strings, for example [\"10.25\", \"4.75\"]. Do not include " +
                            "currency symbols."
            )
            List<String> amounts,
            @ToolParam(
                    name = "currency",
                    description = "Optional currency code, for example USD, CAD, EUR"
            )
            Optional<String> currency,
            @ToolParam(
                    name = "scale",
                    description = "Decimal scale for the final answer. Defaults to 2"
            )
            Optional<Integer> scale,
            @ToolParam(
                    name = "roundingMode",
                    description = "Java RoundingMode name. Defaults to HALF_UP. Examples: HALF_UP, HALF_EVEN, DOWN"
            )
            Optional<String> roundingMode
    ) {
        if (amounts == null || amounts.isEmpty()) {
            throw new IllegalArgumentException("amounts cannot be empty");
        }

        int finalScale = scale.orElse(2);
        if (finalScale < 0 || finalScale > 12) {
            throw new IllegalArgumentException("scale must be between 0 and 12");
        }

        RoundingMode rm = roundingMode
                .map(s -> RoundingMode.valueOf(s.toUpperCase(Locale.ROOT)))
                .orElse(RoundingMode.HALF_UP);

        List<BigDecimal> xs = amounts.stream().map(CalculatorTool::parseDecimal).toList();
        String op = operation.toUpperCase(Locale.ROOT);

        BigDecimal raw;
        List<String> warnings = new ArrayList<>();

        switch (op) {
            case "ADD", "ADDITION", "PLUS", "+" -> {
                raw = BigDecimal.ZERO;
                for (BigDecimal x : xs) raw = raw.add(x);
            }
            case "SUBTRACT", "SUBTRACTION", "MINUS", "-" -> {
                raw = xs.getFirst();
                for (int i = 1; i < xs.size(); i++) raw = raw.subtract(xs.get(i));
            }
            case "MULTIPLY", "MULTIPLICATION", "TIMES", "*" -> {
                raw = xs.getFirst();
                for (BigDecimal x : xs) raw = raw.multiply(x);
            }
            case "DIVIDE", "DIVISION", "/" -> {
                requireCount(xs, 2, "DIVIDE");
                if (xs.get(1).compareTo(BigDecimal.ZERO) == 0) {
                    throw new ArithmeticException("Divide by zero");
                }
                raw = xs.get(0).divide(xs.get(1), new MathContext(34, rm));
            }
            case "PERCENT", "PERCENTAGE", "%" -> {
                requireCount(xs, 2, "PERCENT");
                raw = xs.get(0).multiply(xs.get(1)).divide(new BigDecimal("100"), new MathContext(34, rm));
            }
            case "PERCENT_CHANGE" -> {
                requireCount(xs, 2, "PERCENT_CHANGE");
                BigDecimal oldValue = xs.get(0);
                BigDecimal newValue = xs.get(1);
                if (oldValue.compareTo(BigDecimal.ZERO) == 0) {
                    throw new ArithmeticException("percent change from zero is undefined");
                }
                raw = newValue
                        .subtract(oldValue)
                        .divide(oldValue, new MathContext(34, rm))
                        .multiply(new BigDecimal("100"));
            }
            default -> throw new IllegalArgumentException("unsupported accounting operation: " + operation);
        }

        BigDecimal rounded = raw.setScale(finalScale, rm);
        String ccy = currency.orElse("").trim();

        String display = ccy.isBlank()
                ? rounded.toPlainString()
                : ccy.toUpperCase(Locale.ROOT) + " " + rounded.toPlainString();

        return new CalculatorResult(
                rounded.toPlainString(),
                display,
                "BigDecimal; scale=" + finalScale + "; rounding=" + rm,
                warnings
        );

    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null || !DECIMAL.matcher(s.trim()).matches()) {
            throw new IllegalArgumentException("invalid decimal amount: " + s);
        }
        return new BigDecimal(s.trim());
    }

    private static void requireCount(List<BigDecimal> xs, int n, String op) {
        if (xs.size() != n) {
            throw new IllegalArgumentException(op + " requires exactly " + n + " amounts");
        }
    }
}
