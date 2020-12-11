package io.horizon.ftdc.adaptor.converter;

import java.util.function.Function;

import org.slf4j.Logger;

import ctp.thostapi.CThostFtdcInputOrderActionField;
import io.horizon.definition.market.instrument.Instrument;
import io.horizon.definition.market.instrument.PriceMultiplier;
import io.horizon.definition.order.Order;
import io.horizon.ftdc.adaptor.consts.FtdcActionFlag;
import io.mercury.common.log.CommonLoggerFactory;

/**
 * 
 * @author yellow013
 *
 */
public final class ToFtdcInputOrderActionFunc implements Function<Order, CThostFtdcInputOrderActionField> {

	private static final Logger log = CommonLoggerFactory.getLogger(FromFtdcTradeFunc.class);
	
	@Override
	public CThostFtdcInputOrderActionField apply(Order order) {
		Instrument instrument = order.instrument();
		CThostFtdcInputOrderActionField inputOrderActionField = new CThostFtdcInputOrderActionField();
		/**
		 * 
		 */
		inputOrderActionField.setActionFlag(FtdcActionFlag.Delete);
		/**
		 * 设置交易所ID
		 */
		inputOrderActionField.setExchangeID(instrument.exchange().code());
		/**
		 * 设置交易标的
		 */
		inputOrderActionField.setInstrumentID(instrument.code());

		PriceMultiplier multiplier = instrument.getPriceMultiplier();
		/**
		 * 
		 */
		inputOrderActionField.setLimitPrice(multiplier.toDouble(order.price().offerPrice()));
		/**
		 * 
		 */
		inputOrderActionField.setVolumeChange(order.qty().leavesQty());

		// TODO 补充完整信息
		
		/**
		 * 返回FTDC撤单对象
		 */
		return inputOrderActionField;
	}

}