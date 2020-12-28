package io.horizon.structure.event;

import io.horizon.structure.event.handler.AdaptorEventHandler;
import io.horizon.structure.event.handler.MarketDataHandler;
import io.horizon.structure.event.handler.OrdReportHandler;
import io.horizon.structure.market.data.MarketData;

/**
 * 
 * 处理Adaptor的入站信息抽象
 * 
 * @author yellow013
 *
 * @param <M>
 */
public interface InboundScheduler<M extends MarketData>
		extends MarketDataHandler<M>, OrdReportHandler, AdaptorEventHandler {

}
