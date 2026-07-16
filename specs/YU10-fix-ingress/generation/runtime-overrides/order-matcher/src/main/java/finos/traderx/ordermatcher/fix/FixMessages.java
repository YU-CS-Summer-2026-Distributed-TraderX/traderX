package finos.traderx.ordermatcher.fix;

import finos.traderx.ordermatcher.api.OrderCreateRequest;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FIX 4.4 message construction + the field mapping recorded in data-model.md. Kept engine-agnostic
 * and static so the translator, submitter, and report-sender threads share one implementation.
 * All senders swallow QuickFIX/J send failures into a WARN — a dead session's reports are already
 * covered by the persistent store (resend window) and OrderStatusRequest (ADR-037).
 */
final class FixMessages {
    private static final Logger log = LoggerFactory.getLogger(FixMessages.class);
    private static final Pattern ORDER_ID = Pattern.compile("^ord-013-(\\d{4,})$");
    private static final AtomicLong EXEC_ID = new AtomicLong();

    private FixMessages() { }

    static String publicOrderId(int orderRef) {
        return String.format("ord-013-%04d", orderRef);
    }

    static int parseOrderRef(String orderId) {
        if (orderId == null) {
            return -1;
        }
        Matcher m = ORDER_ID.matcher(orderId);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    static OrderCreateRequest toCreateRequest(String clOrdId, int accountId, String compId,
                                              String ticker, char fixSide, int qty, BigDecimal px) {
        OrderCreateRequest r = new OrderCreateRequest();
        // Namespace the engine idempotency key by session so a REST client and a FIX client (or
        // two FIX sessions) using the same literal id can never collide (FR-IMRG14 interplay).
        r.setClientOrderId("fix:" + compId + ":" + clOrdId);
        r.setAccountId(accountId);
        r.setSecurity(ticker);
        r.setSide(fixSide == '1' ? OrderSide.Buy : OrderSide.Sell);
        r.setQuantity(qty);
        r.setLimitPrice(px);
        return r;
    }

    /** Admission report from the sequenced batch response: New (with actual fill state) or Rejected. */
    static void sendAdmissionReport(SessionID sessionId, String clOrdId, OrderResponse r) {
        boolean rejected = r.getStatus() == OrderStatus.REJECTED;
        Message er = baseReport(clOrdId, r.getOrderId(), rejected ? '8' : '0', statusChar(r.getStatus()));
        er.setString(55, r.getSecurity());
        er.setChar(54, r.getSide() == OrderSide.Buy ? '1' : '2');
        er.setDouble(38, r.getQuantity() == null ? 0 : r.getQuantity());
        if (r.getLimitPrice() != null) {
            er.setDecimal(44, r.getLimitPrice());
        }
        setQuantities(er, r);
        if (rejected && r.getRiskReason() != null) {
            er.setString(58, r.getRiskReason());   // Text(58): POSITION_LIMIT, CREDIT_LIMIT, ...
        }
        send(er, sessionId);
    }

    /** Cancel confirmation ('4') or status snapshot ('I') built from a service response. */
    static void sendReportFromResponse(SessionID sessionId, String clOrdId, OrderResponse r, char execType) {
        Message er = baseReport(clOrdId, r.getOrderId(), execType, statusChar(r.getStatus()));
        er.setString(55, r.getSecurity());
        er.setChar(54, r.getSide() == OrderSide.Buy ? '1' : '2');
        er.setDouble(38, r.getQuantity() == null ? 0 : r.getQuantity());
        setQuantities(er, r);
        send(er, sessionId);
    }

    /** Post-admission lifecycle report (fill/cancel) from an output-ring job. */
    static void sendLifecycleReport(FixExecutionReportHandler.ReportJob job, FixOrderRegistry.Ctx ctx,
                                    char execType, char ordStatus) {
        Message er = baseReport(ctx.clOrdId(), publicOrderId(job.orderRef()), execType, ordStatus);
        er.setString(55, ctx.ticker());
        er.setChar(54, ctx.fixSide());
        er.setDouble(38, ctx.quantity());
        er.setDouble(151, job.remainingQty());
        er.setDouble(14, (double) ctx.quantity() - job.remainingQty());
        if (job.lastFillQty() > 0) {
            er.setDouble(32, job.lastFillQty());
            er.setDouble(31, ticksToPrice(FixExecutionReportHandler.pxOrZero(job.lastExecPx())));
        }
        send(er, ctx.sessionId());
    }

    static void sendCancelReject(SessionID sessionId, String clOrdId, String origClOrdId, String reason) {
        Message m = new Message();
        m.getHeader().setString(35, "9");                    // OrderCancelReject
        m.setString(37, "NONE");                             // OrderID
        m.setString(11, clOrdId == null ? "NONE" : clOrdId);
        m.setString(41, origClOrdId == null ? "NONE" : origClOrdId);
        m.setChar(39, '8');                                  // OrdStatus: Rejected
        m.setChar(434, '1');                                 // CxlRejResponseTo: cancel request
        if (reason != null) {
            m.setString(58, reason);
        }
        send(m, sessionId);
    }

    /** Session-level application reject: pre-publish failures, malformed/unsupported messages. */
    static void sendBusinessReject(SessionID sessionId, String refMsgType, String refId, String reason) {
        Message m = new Message();
        m.getHeader().setString(35, "j");                    // BusinessMessageReject
        m.setString(372, refMsgType);                        // RefMsgType
        m.setInt(380, 0);                                    // BusinessRejectReason: other
        if (refId != null) {
            m.setString(379, refId);                         // BusinessRejectRefID = ClOrdID
        }
        if (reason != null) {
            m.setString(58, reason);
        }
        send(m, sessionId);
    }

    // ---- internals -----------------------------------------------------------------------------

    private static Message baseReport(String clOrdId, String orderId, char execType, char ordStatus) {
        Message er = new Message();
        er.getHeader().setString(35, "8");                   // ExecutionReport
        er.setString(37, orderId == null ? "NONE" : orderId);
        er.setString(17, "exec-" + EXEC_ID.incrementAndGet());
        er.setChar(150, execType);
        er.setChar(39, ordStatus);
        er.setString(11, clOrdId);
        er.setDouble(151, 0);                                // LeavesQty default; overwritten when known
        er.setDouble(14, 0);                                 // CumQty default
        er.setDouble(6, 0);                                  // AvgPx (not tracked per order)
        return er;
    }

    private static void setQuantities(Message er, OrderResponse r) {
        int qty = r.getQuantity() == null ? 0 : r.getQuantity();
        int remaining = r.getRemainingQuantity() == null ? qty : r.getRemainingQuantity();
        er.setDouble(151, remaining);
        er.setDouble(14, qty - remaining);
        if (r.getLastFillQuantity() != null && r.getLastFillQuantity() > 0 && r.getLastExecutionPrice() != null) {
            er.setDouble(32, r.getLastFillQuantity());
            er.setDecimal(31, r.getLastExecutionPrice());
        }
    }

    private static char statusChar(OrderStatus status) {
        if (status == null) {
            return '0';
        }
        return switch (status) {
            case NEW -> '0';
            case PARTIALLY_FILLED -> '1';
            case FILLED -> '2';
            case CANCELED -> '4';
            case REJECTED -> '8';
        };
    }

    /** Px ticks -> price double for LastPx(31); mirrors the fixed-point scale REST responses use. */
    private static double ticksToPrice(long ticks) {
        return ticks / 1000.0d;
    }

    private static void send(Message message, SessionID sessionId) {
        try {
            Session.sendToTarget(message, sessionId);
        } catch (Exception ex) {
            log.warn("FIX send failed to {}: {}", sessionId, ex.toString());
        }
    }
}
