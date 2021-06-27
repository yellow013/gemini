package io.horizon.trader.adaptor.bean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public final class PositionsReport {

	private final int investorId;
	private final String instrumentCode;
	private final int currentQty;

}
