package dev.toka.economy.core;

import java.math.BigDecimal;
import java.util.List;

/** The fee settings: base percentage, general minimum, rules. */
public record Fee(BigDecimal ratePercent, long min, List<FeeRule> rules) {
}
