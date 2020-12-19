package io.horizon.ftdc.adaptor;

import static io.mercury.common.thread.Threads.sleep;
import static io.mercury.common.thread.Threads.startNewThread;

import java.io.IOException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.slf4j.Logger;

import ctp.thostapi.CThostFtdcInputOrderActionField;
import ctp.thostapi.CThostFtdcInputOrderField;
import io.horizon.definition.account.Account;
import io.horizon.definition.adaptor.AdaptorBaseImpl;
import io.horizon.definition.adaptor.AdaptorEvent;
import io.horizon.definition.adaptor.AdaptorEvent.AdaptorStatus;
import io.horizon.definition.adaptor.Command;
import io.horizon.definition.event.InboundScheduler;
import io.horizon.definition.market.data.impl.BasicMarketData;
import io.horizon.definition.market.instrument.Instrument;
import io.horizon.definition.order.OrdReport;
import io.horizon.definition.order.actual.ChildOrder;
import io.horizon.ftdc.adaptor.converter.FromFtdcDepthMarketDataFunc;
import io.horizon.ftdc.adaptor.converter.FromFtdcOrderFunc;
import io.horizon.ftdc.adaptor.converter.FromFtdcTradeFunc;
import io.horizon.ftdc.adaptor.converter.ToFtdcInputOrderActionFunc;
import io.horizon.ftdc.adaptor.converter.ToFtdcInputOrderFunc;
import io.horizon.ftdc.exception.OrderRefNotFoundException;
import io.horizon.ftdc.gateway.FtdcConfig;
import io.horizon.ftdc.gateway.FtdcGateway;
import io.horizon.ftdc.gateway.bean.FtdcInputOrder;
import io.horizon.ftdc.gateway.bean.FtdcInputOrderAction;
import io.horizon.ftdc.gateway.bean.FtdcMdConnect;
import io.horizon.ftdc.gateway.bean.FtdcOrder;
import io.horizon.ftdc.gateway.bean.FtdcOrderAction;
import io.horizon.ftdc.gateway.bean.FtdcTrade;
import io.horizon.ftdc.gateway.bean.FtdcTraderConnect;
import io.mercury.common.concurrent.queue.jct.JctScQueue;
import io.mercury.common.log.CommonLoggerFactory;
import io.mercury.common.param.Params;
import io.mercury.serialization.json.JsonUtil;

public class FtdcAdaptor extends AdaptorBaseImpl<BasicMarketData> {

	private static final Logger log = CommonLoggerFactory.getLogger(FtdcAdaptor.class);

	/**
	 * 转换行情
	 */
	private final FromFtdcDepthMarketDataFunc fromFtdcDepthMarketData = new FromFtdcDepthMarketDataFunc();

	/**
	 * 转换报单回报
	 */
	private final FromFtdcOrderFunc fromFtdcOrder = new FromFtdcOrderFunc();

	/**
	 * 转换成交回报
	 */
	private final FromFtdcTradeFunc fromFtdcTrade = new FromFtdcTradeFunc();

	/**
	 * FTDC Gateway
	 */
	private final FtdcGateway gateway;

	// TODO 两个INT类型可以合并
	private volatile int frontId;
	private volatile int sessionId;

	private volatile boolean isMdAvailable;
	private volatile boolean isTraderAvailable;

	public FtdcAdaptor(final int adaptorId, @Nonnull final Account account,
			@Nonnull final Params<FtdcAdaptorParamKey> params,
			@Nonnull final InboundScheduler<BasicMarketData> scheduler) {
		super(adaptorId, "FtdcAdaptor-Broker[" + account.brokerName() + "]-InvestorId[" + account.investorId() + "]",
				scheduler, account);
		// 创建配置信息
		FtdcConfig ftdcConfig = createFtdcConfig(params);
		// 创建Gateway
		this.gateway = createFtdcGateway(ftdcConfig);
		this.toFtdcInputOrder = new ToFtdcInputOrderFunc();
		this.toFtdcInputOrderAction = new ToFtdcInputOrderActionFunc();
	}

	/**
	 * 
	 * @param params
	 * @return
	 */
	private FtdcConfig createFtdcConfig(Params<FtdcAdaptorParamKey> params) {
		return new FtdcConfig()
				// 交易服务器地址
				.setTraderAddr(params.getString(FtdcAdaptorParamKey.TraderAddr))
				// 行情服务器地址
				.setMdAddr(params.getString(FtdcAdaptorParamKey.MdAddr))
				// 应用ID
				.setAppId(params.getString(FtdcAdaptorParamKey.AppId))
				// 经纪商ID
				.setBrokerId(params.getString(FtdcAdaptorParamKey.BrokerId))
				// 投资者ID
				.setInvestorId(params.getString(FtdcAdaptorParamKey.InvestorId))
				// 账号ID
				.setAccountId(params.getString(FtdcAdaptorParamKey.AccountId))
				// 用户ID
				.setUserId(params.getString(FtdcAdaptorParamKey.UserId))
				// 密码
				.setPassword(params.getString(FtdcAdaptorParamKey.Password))
				// 认证码
				.setAuthCode(params.getString(FtdcAdaptorParamKey.AuthCode))
				// 客户端IP地址
				.setIpAddr(params.getString(FtdcAdaptorParamKey.IpAddr))
				// 客户端MAC地址
				.setMacAddr(params.getString(FtdcAdaptorParamKey.MacAddr))
				// 结算货币
				.setCurrencyId(params.getString(FtdcAdaptorParamKey.CurrencyId));
	}

	/**
	 * 
	 * @param ftdcConfig
	 * @return
	 */
	private FtdcGateway createFtdcGateway(final FtdcConfig config) {
		String gatewayId = "FTDC-" + config.getBrokerId() + "-" + config.getUserId();
		log.info("Create Ftdc Gateway, gatewayId -> {}", gatewayId);
		return new FtdcGateway(gatewayId, config,
				// 创建队列缓冲区
				JctScQueue.mpsc(gatewayId + "-Buffer").capacity(64).buildWithProcessor(ftdcRspMsg -> {
					switch (ftdcRspMsg.getRspType()) {
					case FtdcMdConnect:
						FtdcMdConnect mdConnect = ftdcRspMsg.getFtdcMdConnect();
						this.isMdAvailable = mdConnect.isAvailable();
						log.info("Swap Queue processed FtdcMdConnect, isMdAvailable==[{}]", isMdAvailable);
						final AdaptorEvent mdEvent;
						if (mdConnect.isAvailable()) {
							mdEvent = new AdaptorEvent(adaptorId(), AdaptorStatus.MdEnable);
						} else {
							mdEvent = new AdaptorEvent(adaptorId(), AdaptorStatus.MdDisable);
						}
						scheduler.onAdaptorEvent(mdEvent);
						break;
					case FtdcTraderConnect:
						FtdcTraderConnect traderConnect = ftdcRspMsg.getFtdcTraderConnect();
						this.isTraderAvailable = traderConnect.isAvailable();
						this.frontId = traderConnect.getFrontID();
						this.sessionId = traderConnect.getSessionID();
						log.info(
								"Swap Queue processed FtdcTraderConnect, "
										+ "isTraderAvailable==[{}], frontId==[{}], sessionId==[{}]",
								isTraderAvailable, frontId, sessionId);
						final AdaptorEvent traderEvent;
						if (traderConnect.isAvailable()) {
							traderEvent = new AdaptorEvent(adaptorId(), AdaptorStatus.TraderEnable);
						} else {
							traderEvent = new AdaptorEvent(adaptorId(), AdaptorStatus.TraderDisable);
						}
						scheduler.onAdaptorEvent(traderEvent);
						break;
					case FtdcDepthMarketData:
						// 行情处理
						BasicMarketData marketData = fromFtdcDepthMarketData.apply(ftdcRspMsg.getFtdcDepthMarketData());
						scheduler.onMarketData(marketData);
						break;
					case FtdcOrder:
						// 报单回报处理
						FtdcOrder ftdcOrder = ftdcRspMsg.getFtdcOrder();
						log.info("Buffer Queue in FtdcOrder, InstrumentID==[{}], InvestorID==[{}], "
								+ "OrderRef==[{}], LimitPrice==[{}], VolumeTotalOriginal==[{}], OrderStatus==[{}]",
								ftdcOrder.getInstrumentID(), ftdcOrder.getInvestorID(), ftdcOrder.getOrderRef(),
								ftdcOrder.getLimitPrice(), ftdcOrder.getVolumeTotalOriginal(),
								ftdcOrder.getOrderStatus());
						OrdReport ordReport = fromFtdcOrder.apply(ftdcOrder);
						scheduler.onOrdReport(ordReport);
						break;
					case FtdcTrade:
						// 成交回报处理
						FtdcTrade ftdcTrade = ftdcRspMsg.getFtdcTrade();
						log.info("Buffer Queue in FtdcTrade, InstrumentID==[{}], InvestorID==[{}], OrderRef==[{}]",
								ftdcTrade.getInstrumentID(), ftdcTrade.getInvestorID(), ftdcTrade.getOrderRef());
						OrdReport trdReport = fromFtdcTrade.apply(ftdcTrade);
						scheduler.onOrdReport(trdReport);
						break;
					case FtdcInputOrder:
						// TODO 报单错误处理
						FtdcInputOrder ftdcInputOrder = ftdcRspMsg.getFtdcInputOrder();
						log.info("Buffer Queue in [FtdcInputOrder] -> {}", JsonUtil.toJson(ftdcInputOrder));
						break;
					case FtdcInputOrderAction:
						// TODO 撤单错误处理1
						FtdcInputOrderAction ftdcInputOrderAction = ftdcRspMsg.getFtdcInputOrderAction();
						log.info("Buffer Queue in [FtdcInputOrderAction] -> {}", JsonUtil.toJson(ftdcInputOrderAction));
						break;
					case FtdcOrderAction:
						// TODO 撤单错误处理2
						FtdcOrderAction ftdcOrderAction = ftdcRspMsg.getFtdcOrderAction();
						log.info("Buffer Queue in [FtdcOrderAction] -> {}", JsonUtil.toJson(ftdcOrderAction));
						break;
					default:
						log.warn("Buffer Queue unprocessed [FtdcRspMsg] -> {}", JsonUtil.toJson(ftdcRspMsg));
						break;
					}
				}));
		// JctMpscQueue.autoStartQueue(gatewayId + "-Buffer", 64,
		// WaitingStrategy.SpinWaiting, ));
	}

	@Override
	protected boolean startup0() {
		try {
			gateway.bootstrap();
			log.info("");
			return true;
		} catch (Exception e) {
			log.error("Gateway ", e);
			return false;
		}
	}

	/**
	 * 订阅行情实现
	 */
	@Override
	public boolean subscribeMarketData(Instrument... instruments) {
		try {
			if (isMdAvailable) {
				gateway.SubscribeMarketData(Stream.of(instruments).map(Instrument::instrumentCode).collect(Collectors.toSet()));
				return true;
			} else {
				return false;
			}
		} catch (Exception e) {
			log.error("ftdcGateway#SubscribeMarketData exception -> {}", e.getMessage(), e);
			return false;
		}
	}

	/**
	 * 订单转换为FTDC新订单
	 */
	private final ToFtdcInputOrderFunc toFtdcInputOrder;

	@Override
	public boolean newOredr(Account account, ChildOrder order) {
		try {
			CThostFtdcInputOrderField ftdcInputOrder = toFtdcInputOrder.apply(order);
			String orderRef = Integer.toString(OrderRefGenerator.next(order.strategyId()));
			/**
			 * 设置OrderRef
			 */
			ftdcInputOrder.setOrderRef(orderRef);
			OrderRefKeeper.put(orderRef, order.ordId());
			gateway.ReqOrderInsert(ftdcInputOrder);
			return true;
		} catch (Exception e) {
			log.error("#############################################################");
			log.error("ftdcGateway.ReqOrderInsert exception -> {}", e.getMessage(), e);
			log.error("#############################################################");
			return false;
		}
	}

	/**
	 * 订单转换为FTDC撤单
	 */
	private final ToFtdcInputOrderActionFunc toFtdcInputOrderAction;

	@Override
	public boolean cancelOrder(Account account, ChildOrder order) {
		try {
			CThostFtdcInputOrderActionField ftdcInputOrderAction = toFtdcInputOrderAction.apply(order);
			String orderRef = OrderRefKeeper.getOrderRef(order.ordId());

			ftdcInputOrderAction.setOrderRef(orderRef);
			ftdcInputOrderAction.setOrderActionRef(OrderRefGenerator.next(order.strategyId()));
			gateway.ReqOrderAction(ftdcInputOrderAction);
			return true;
		} catch (OrderRefNotFoundException e) {
			log.error(e.getMessage(), e);
			return false;
		} catch (Exception e) {
			log.error("ftdcGateway.ReqOrderAction exception -> {}", e.getMessage(), e);
			return false;
		}
	}

	private final Object mutex = new Object();

	@Override
	public boolean queryOrder(Account account, @Nonnull Instrument instrument) {
		try {
			if (isTraderAvailable) {
				startNewThread("QueryOrder-SubThread", () -> {
					synchronized (mutex) {
						log.info("FtdcAdaptor :: Ready to sent ReqQryInvestorPosition, Waiting...");
						sleep(1500);
						gateway.ReqQryOrder(instrument.exchangeCode());
						log.info("FtdcAdaptor :: Has been sent ReqQryInvestorPosition");
					}
				});
				return true;
			} else {
				return false;
			}
		} catch (Exception e) {
			log.error("ftdcGateway.ReqQryOrder exception -> {}", e.getMessage(), e);
			return false;
		}
	}

	@Override
	public boolean queryPositions(Account account, @Nonnull Instrument instrument) {
		try {
			if (isTraderAvailable) {
				startNewThread("QueryPositions-SubThread", () -> {
					synchronized (mutex) {
						log.info("FtdcAdaptor :: Ready to sent ReqQryInvestorPosition, Waiting...");
						sleep(1500);
						gateway.ReqQryInvestorPosition(instrument.exchangeCode(), instrument.instrumentCode());
						log.info("FtdcAdaptor :: Has been sent ReqQryInvestorPosition");
					}
				});
				return true;
			} else {
				return false;
			}
		} catch (Exception e) {
			log.error("ftdcGateway.ReqQryInvestorPosition exception -> {}", e.getMessage(), e);
			return false;
		}
	}

	@Override
	public boolean queryBalance(Account account) {
		try {
			if (isTraderAvailable) {
				startNewThread("QueryBalance-SubThread", () -> {
					synchronized (mutex) {
						log.info("FtdcAdaptor :: Ready to sent ReqQryTradingAccount, Waiting...");
						sleep(1500);
						gateway.ReqQryTradingAccount();
						log.info("FtdcAdaptor :: Has been sent ReqQryTradingAccount");
					}
				});
				return true;
			} else {
				return false;
			}
		} catch (Exception e) {
			log.error("ftdcGateway.ReqQryTradingAccount exception -> {}", e.getMessage(), e);
			return false;
		}
	}

	@Override
	public void close() throws IOException {
		// TODO close adaptor
	}

	@Override
	public boolean sendCommand(Command command) {
		// TODO Auto-generated method stub
		return false;
	}

}
