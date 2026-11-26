package com.lesconstructionssapete.stempyerpserver.modules.gl.dto;

import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRule;

/** One rule of an account: what its lines must or may not carry. */
public record DimensionRuleView(String dimensionCode, DimensionRule rule) {
}
