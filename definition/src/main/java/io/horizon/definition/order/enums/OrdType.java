package io.horizon.definition.order.enums;

public enum OrdType {

	Invalid(-1),

	Limit(1),

	Market(1 << 1),

	Stop(1 << 2),

	StopLimit(1 << 3),

	FOK(1 << 4),

	FAK(1 << 5),

	;

	private int code;

	private OrdType(int code) {
		this.code = code;
	}

	public int code() {
		return code;
	}

}
