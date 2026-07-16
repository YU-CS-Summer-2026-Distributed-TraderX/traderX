package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.auth.JwtTokenMinter;
import finos.traderx.ordermatcher.lmax.InMemoryOrderReadModel;
import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.lmax.OrderSnapshot;
import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import finos.traderx.ordermatcher.risk.GatewayReplicaStore;
import finos.traderx.ordermatcher.risk.RiskReason;
import finos.traderx.ordermatcher.service.OrderMatcherService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** MVC-level 401/403/allowed matrix for every trading mutation (FR-PTC42). */
class CommandAuthorizationControllerTest {
    private static final String SECRET = "command-controller-test-secret";
    private static final int ACCOUNT_A = 22214;
    private static final int ACCOUNT_B = 44044;
    private static final int ORDER_REF = 1000;
    private static final String ORDER_ID = "ord-013-1000";

    private enum Surface {
        CREATE,
        BATCH,
        MARKET_TRADE,
        CANCEL,
        FORCE_FILL
    }

    private MockMvc mvc;
    private String entitled;
    private String foreign;
    private String admin;

    @BeforeEach
    void setUp() {
        LmaxEngine engine = mock(LmaxEngine.class);
        GatewayReplicaStore replicas = mock(GatewayReplicaStore.class);
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        OrderSnapshot accountAOrder = snapshot(ORDER_REF, ACCOUNT_A);
        OrderSnapshot accountBOrder = snapshot(ORDER_REF + 1, ACCOUNT_B);
        readModel.bootstrap(accountAOrder);

        when(engine.readModel()).thenReturn(readModel);
        AtomicInteger nextOrderRef = new AtomicInteger(ORDER_REF + 10);
        when(engine.nextOrderRef()).thenAnswer(ignored -> nextOrderRef.getAndIncrement());
        when(engine.executeNewOrder(anyInt(), anyInt(), anyString(), any(OrderSide.class),
            anyInt(), any(BigDecimal.class), anyLong())).thenReturn(accountAOrder);
        when(engine.executeNewOrderBatch(any())).thenReturn(List.of(accountAOrder, accountBOrder));
        when(engine.executeCancel(ORDER_REF)).thenReturn(accountAOrder);
        when(engine.executeForceFill(ORDER_REF)).thenReturn(accountAOrder);
        when(engine.executeTradeNew(anyInt(), anyString(), any(OrderSide.class), anyInt(), anyLong()))
            .thenReturn(new LmaxEngine.RiskDecision(RiskReason.ACCEPTED, 1L));

        OrderMatcherService service = new OrderMatcherService(
            engine, replicas, false, SECRET, true,
            "http://price-publisher:18100", "http://trade-service:18092/trade/");
        mvc = standaloneSetup(new OrderController(service), new MarketTradeController(service)).build();

        JwtTokenMinter minter = new JwtTokenMinter(SECRET);
        entitled = "Bearer " + minter.mint("trader", Set.of(ACCOUNT_A, ACCOUNT_B), false, 600L);
        foreign = "Bearer " + minter.mint("other", Set.of(99999), false, 600L);
        admin = "Bearer " + minter.mint("admin", Set.of(), true, 600L);
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void missingTokenIs401OnEveryMutation(Surface surface) throws Exception {
        perform(surface, null).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void invalidTokenIs401OnEveryMutation(Surface surface) throws Exception {
        perform(surface, "Bearer not.a.jwt").andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void validForeignAccountIs403OnEveryMutation(Surface surface) throws Exception {
        perform(surface, foreign).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void entitledPrincipalKeepsExistingBehavior(Surface surface) throws Exception {
        perform(surface, entitled).andExpect(surface == Surface.CREATE || surface == Surface.BATCH
            ? status().isCreated() : status().isOk());
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void adminPrincipalKeepsExistingBehavior(Surface surface) throws Exception {
        perform(surface, admin).andExpect(surface == Surface.CREATE || surface == Surface.BATCH
            ? status().isCreated() : status().isOk());
    }

    private ResultActions perform(Surface surface, String authorization) throws Exception {
        var request = switch (surface) {
            case CREATE -> post("/orders")
                .contentType("application/json")
                .content(orderJson("create-1", ACCOUNT_A));
            case BATCH -> post("/orders/batch")
                .contentType("application/json")
                .content("[" + orderJson("batch-1", ACCOUNT_A) + ","
                    + orderJson("batch-2", ACCOUNT_B) + "]");
            case MARKET_TRADE -> post("/trades")
                .contentType("application/json")
                .content("""
                    {"clientOrderId":"trade-1","accountId":%d,"security":"IBM",\
                    "side":"Buy","quantity":10}
                    """.formatted(ACCOUNT_A));
            case CANCEL -> post("/orders/{orderId}/cancel", ORDER_ID);
            case FORCE_FILL -> post("/orders/{orderId}/force-fill", ORDER_ID);
        };
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mvc.perform(request);
    }

    private static String orderJson(String clientOrderId, int accountId) {
        return """
            {"clientOrderId":"%s","accountId":%d,"security":"IBM",\
            "side":"Buy","quantity":10,"limitPrice":100.000}
            """.formatted(clientOrderId, accountId);
    }

    private static OrderSnapshot snapshot(int orderRef, int accountId) {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        OrderRecord record = new OrderRecord();
        record.setOrderId(OrderSnapshot.orderIdFor(orderRef));
        record.setAccountId(accountId);
        record.setSecurity("IBM");
        record.setSide(OrderSide.Buy);
        record.setQuantity(10);
        record.setRemainingQuantity(10);
        record.setLimitPrice(new BigDecimal("100.000"));
        record.setStatus(OrderStatus.NEW);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return OrderSnapshot.fromRecord(orderRef, record);
    }
}
