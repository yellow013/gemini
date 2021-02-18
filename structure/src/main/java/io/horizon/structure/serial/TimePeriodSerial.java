package io.horizon.structure.serial;

import java.time.Duration;
import java.time.ZonedDateTime;

import io.mercury.common.sequence.Serial;
import io.mercury.common.util.Assertor;
import lombok.Getter;

/**
 * 时间周期序列
 * 
 * @author yellow013
 */
public final class TimePeriodSerial implements Serial<TimePeriodSerial> {

	@Getter
	private final long epochSecond;
	
	@Getter
	private final Duration duration;
	
	@Getter
	private final ZonedDateTime startTime;
	
	@Getter
	private final ZonedDateTime endTime;

	public static TimePeriodSerial newSerial(ZonedDateTime startTime, ZonedDateTime endTime, Duration duration) {
		Assertor.nonNull(startTime, "startTime");
		Assertor.nonNull(endTime, "endTime");
		return new TimePeriodSerial(startTime, endTime, duration);
	}

	private TimePeriodSerial(ZonedDateTime startTime, ZonedDateTime endTime, Duration duration) {
		this.startTime = startTime;
		this.endTime = endTime;
		this.duration = duration;
		this.epochSecond = startTime.toEpochSecond();
	}

	@Override
	public long getSerialId() {
		return epochSecond;
	}

	public boolean isPeriod(ZonedDateTime time) {
		return startTime.isBefore(time) && endTime.isAfter(time) ? true : false;
	}

	private String toStringCache;

	@Override
	public String toString() {
		if (toStringCache == null)
			toStringCache = epochSecond + " -> [" + startTime.getZone() + "][" + startTime.toLocalDateTime() + " - "
					+ endTime.toLocalDateTime() + "]";
		return toStringCache;
	}

}