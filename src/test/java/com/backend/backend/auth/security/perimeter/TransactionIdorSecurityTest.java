package com.backend.backend.auth.security.perimeter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.security.SecurityIntegrationTestBase;
import com.backend.backend.transaction.Transaction;

public class TransactionIdorSecurityTest extends SecurityIntegrationTestBase {

        private static final String VICTIM = "victim@gmail.com";
        private static final String ATTACKER = "attacker@gmail.com";
        private static final String RECEIVER = "receiver@gmail.com";
        private static final String ADMIN = "admin@gmail.com";

        private static final String BASE = "/api/v1/user/transaction";
        private static final String TOP_UP_URL = BASE + "/create/top-up";
        private static final String WITHDRAW_URL = BASE + "/create/withdraw";
        private static final String TRANSFER_URL = BASE + "/create/transfer";
        private static final String SEND_OTP_URL = BASE + "/verify/send-otp";
        private static final String VERIFY_OTP_URL = BASE + "/verify/verify-otp";
        private static final String EXECUTE_URL = BASE + "/execute";
        private static final String OPEN_WALLET_URL = "/api/v1/user/open-wallet";
        private static final String WALLET_URL = "/api/v1/user/wallet-details";

        private static final List<String> VERIFY_ENDPOINTS = List.of("send-otp", "verify-otp", "execute");
        private static final int RACE_ATTACKERS = 7;

        private Tokens victimTokens;
        private Tokens attackerTokens;
        private Tokens receiverTokens;
        private Tokens adminTokens;

        private String victimWallet;
        private String attackerWallet;
        private String receiverWallet;
        private String victimTopUpRef;

        @BeforeEach
        void setUp() throws Exception {
                createUser(VICTIM, PASSWORD, Role.USER, 1, 1);
                createUser(ATTACKER, PASSWORD, Role.USER, 1, 1);
                createUser(RECEIVER, PASSWORD, Role.USER, 1, 1);
                createUser(ADMIN, PASSWORD, Role.ADMIN, 1, 1);

                victimTokens = loginOk(VICTIM, PASSWORD);
                attackerTokens = loginOk(ATTACKER, PASSWORD);
                receiverTokens = loginOk(RECEIVER, PASSWORD);
                adminTokens = loginOk(ADMIN, PASSWORD);

                victimWallet = openWallet(victimTokens);
                attackerWallet = openWallet(attackerTokens);
                receiverWallet = openWallet(receiverTokens);

                victimTopUpRef = createTopUp(victimTokens, 500000);
        }

        static Stream<Arguments> nonOwnerActorsAndEndpoints() {
                return Stream.of("ATTACKER", "ADMIN")
                                .flatMap(actor -> VERIFY_ENDPOINTS.stream()
                                                .map(endpoint -> Arguments.of(actor, endpoint)));
        }

        @ParameterizedTest(name = "{0} gọi {1} trên giao dịch của VICTIM => 403, không dấu vết")
        @MethodSource("nonOwnerActorsAndEndpoints")
        @DisplayName("IDOR: USER khác hoặc ADMIN gọi send-otp, verify-otp, execute trên giao dịch của người khác => 403 và không tác dụng phụ")
        void nonOwner_isForbidden_andLeavesNoTrace(String actor, String endpoint) throws Exception {
                VictimState before = snapshot(victimWallet, victimTopUpRef);
                clearInvocations(emailService);

                MvcResult res = callVerifyEndpoint(endpoint, tokensOf(actor).access(), victimTopUpRef,
                                secretFor(endpoint));

                assertThat(statusOf(res)).as(body(res)).isEqualTo(403);
                assertThat(body(res)).doesNotContain(VICTIM, victimWallet, victimTopUpRef);
                assertNoMailSent();
                assertUnchanged(before, snapshot(victimWallet, victimTopUpRef));
                assertThat(redisKeys(victimTopUpRef)).noneMatch(k -> k.endsWith(":" + emailOf(actor)));
        }

        @ParameterizedTest(name = "chủ ví đích gọi {0} trên giao dịch chuyển tiền của VICTIM => 403")
        @ValueSource(strings = { "send-otp", "verify-otp", "execute" })
        @DisplayName("IDOR: chủ ví ĐÍCH của giao dịch chuyển tiền cũng không được gọi send-otp, verify-otp, execute")
        void destinationOwner_cannotTouchTransfer(String endpoint) throws Exception {
                jdbc.update("UPDATE wallets SET balance = 1000000 WHERE id = ?", victimWallet);
                String transferRef = createTransfer(victimTokens, receiverWallet, 300000);
                VictimState before = snapshot(victimWallet, transferRef);
                clearInvocations(emailService);

                MvcResult res = callVerifyEndpoint(endpoint, receiverTokens.access(), transferRef,
                                secretFor(endpoint));

                assertThat(statusOf(res)).as(body(res)).isEqualTo(403);
                assertNoMailSent();
                assertUnchanged(before, snapshot(victimWallet, transferRef));
                assertThat(balanceOf(receiverWallet)).isEqualByComparingTo("0");
                assertThat(redisKeys(transferRef)).noneMatch(k -> k.endsWith(":" + RECEIVER));
        }

        @Test
        @DisplayName("IDOR: Attacker có OTP thật và token thật của Victim vẫn 403, không đốt OTP/token, Victim execute thành công đúng 1 lần")
        void attackerWithLeakedOtpAndToken_stillForbidden_andVictimFlowRemainsIntact() throws Exception {
                int pendingBefore = pendingOf(victimWallet);
                String realOtp = sendOtpAndCapture(VICTIM, victimTokens.access(), victimTopUpRef);

                VictimState afterSendOtp = snapshot(victimWallet, victimTopUpRef);
                MvcResult attackerVerify = verifyOtpRaw(attackerTokens.access(), victimTopUpRef, realOtp);
                assertThat(statusOf(attackerVerify)).isEqualTo(403);
                assertUnchanged(afterSendOtp, snapshot(victimWallet, victimTopUpRef));

                String realToken = verifyOtpOk(victimTokens.access(), victimTopUpRef, realOtp);

                VictimState afterVerify = snapshot(victimWallet, victimTopUpRef);
                MvcResult attackerExec = executeRaw(attackerTokens.access(), victimTopUpRef, realToken);
                assertThat(statusOf(attackerExec)).isEqualTo(403);
                assertUnchanged(afterVerify, snapshot(victimWallet, victimTopUpRef));
                assertThat(redisKeys(victimTopUpRef)).noneMatch(k -> k.endsWith(":" + ATTACKER));

                MvcResult victimExec = executeRaw(victimTokens.access(), victimTopUpRef, realToken);
                assertThat(statusOf(victimExec)).as(body(victimExec)).isEqualTo(200);
                assertThat(jsonString(victimExec, "$.data.status")).isEqualTo("SUCCESS");

                assertThat(balanceOf(victimWallet)).isEqualByComparingTo("500000");
                assertThat(balanceOf(attackerWallet)).isEqualByComparingTo("0");
                assertThat(txStatus(victimTopUpRef)).isEqualTo("SUCCESS");
                assertThat(pendingOf(victimWallet)).isEqualTo(pendingBefore - 1);
        }

        @Test
        @DisplayName("IDOR: Chủ ví đích có OTP và token thật của giao dịch chuyển tiền vẫn 403, tiền chỉ di chuyển khi Victim execute")
        void receiverWithLeakedOtpAndToken_cannotHijackTransfer() throws Exception {
                jdbc.update("UPDATE wallets SET balance = 1000000 WHERE id = ?", victimWallet);
                String transferRef = createTransfer(victimTokens, receiverWallet, 300000);
                int pendingBefore = pendingOf(victimWallet);

                String realOtp = sendOtpAndCapture(VICTIM, victimTokens.access(), transferRef);
                assertThat(statusOf(verifyOtpRaw(receiverTokens.access(), transferRef, realOtp))).isEqualTo(403);

                String realToken = verifyOtpOk(victimTokens.access(), transferRef, realOtp);
                assertThat(statusOf(executeRaw(receiverTokens.access(), transferRef, realToken))).isEqualTo(403);

                assertThat(balanceOf(victimWallet)).isEqualByComparingTo("1000000");
                assertThat(balanceOf(receiverWallet)).isEqualByComparingTo("0");
                assertThat(txStatus(transferRef)).isEqualTo("PENDING");

                MvcResult victimExec = executeRaw(victimTokens.access(), transferRef, realToken);
                assertThat(statusOf(victimExec)).as(body(victimExec)).isEqualTo(200);
                assertThat(jsonString(victimExec, "$.data.status")).isEqualTo("SUCCESS");

                BigDecimal victimBalance = balanceOf(victimWallet);
                BigDecimal receiverBalance = balanceOf(receiverWallet);
                assertThat(victimBalance).isEqualByComparingTo("700000");
                assertThat(receiverBalance).isEqualByComparingTo("300000");
                assertThat(victimBalance.add(receiverBalance)).isEqualByComparingTo("1000000");
                assertThat(pendingOf(victimWallet)).isEqualTo(pendingBefore - 1);
        }

        @Test
        @DisplayName("IDOR: Attacker spam 10 lần verify-otp sai và send-otp (vượt ngưỡng 5) luôn 403, không làm Victim bị khoá OTP hay hết lượt gửi")
        void attackerSpam_isAlways403_andNeverLocksVictimOut() throws Exception {
                String realOtp = sendOtpAndCapture(VICTIM, victimTokens.access(), victimTopUpRef);
                VictimState before = snapshot(victimWallet, victimTopUpRef);
                clearInvocations(emailService);

                for (int i = 0; i < 10; i++) {
                        assertThat(statusOf(verifyOtpRaw(attackerTokens.access(), victimTopUpRef, "0000000" + i)))
                                        .as("verify-otp lần " + i).isEqualTo(403);
                        assertThat(statusOf(sendOtpRaw(attackerTokens.access(), victimTopUpRef)))
                                        .as("send-otp lần " + i).isEqualTo(403);
                }

                assertNoMailSent();
                assertUnchanged(before, snapshot(victimWallet, victimTopUpRef));
                assertThat(redisKeys(victimTopUpRef)).noneMatch(k -> k.endsWith(":" + ATTACKER));

                String token = verifyOtpOk(victimTokens.access(), victimTopUpRef, realOtp);
                MvcResult exec = executeRaw(victimTokens.access(), victimTopUpRef, token);
                assertThat(statusOf(exec)).as(body(exec)).isEqualTo(200);
                assertThat(balanceOf(victimWallet)).isEqualByComparingTo("500000");
        }

        @Test
        @DisplayName("IDOR: Tạo top-up với body giả sourceWalletId/destinationWalletId/status/referenceId/type => các trường giả bị bỏ qua hoặc bị từ chối")
        void forgedFieldsOnTopUp_areIgnoredOrRejected() throws Exception {
                VictimState victimBefore = snapshot(victimWallet, victimTopUpRef);
                int victimTxBefore = txCountOfWallet(victimWallet);
                String forged = "{\"amount\":1000,\"description\":\"forged\",\"type\":\"WITHDRAW\","
                                + "\"status\":\"SUCCESS\",\"referenceId\":\"FIXED-REF\","
                                + "\"sourceWalletId\":\"" + victimWallet + "\","
                                + "\"destinationWalletId\":\"" + victimWallet + "\"}";

                MvcResult res = postWithToken(TOP_UP_URL, attackerTokens.access(), forged);

                int status = statusOf(res);
                assertThat(status).as(body(res)).isIn(200, 400);
                if (status == 200) {
                        assertThat(jsonString(res, "$.data.referenceId")).isNotEqualTo("FIXED-REF");
                        assertThat(jsonString(res, "$.data.type")).isEqualTo("TOP_UP");
                        assertThat(jsonString(res, "$.data.status")).isEqualTo("PENDING");
                        assertThat(jsonString(res, "$.data.sourceWalletId")).isEqualTo(attackerWallet);
                        assertThat(jsonString(res, "$.data.destinationWalletId")).isEqualTo(attackerWallet);
                        assertThat(balanceOf(attackerWallet)).isEqualByComparingTo("0");
                }
                assertThat(txCountOfWallet(victimWallet)).isEqualTo(victimTxBefore);
                assertUnchanged(victimBefore, snapshot(victimWallet, victimTopUpRef));
        }

        @Test
        @DisplayName("IDOR: Rút tiền với body giả sourceWalletId của Victim => 400, tiền và pending của Victim không đổi")
        void forgedSourceWalletOnWithdraw_cannotDebitVictim() throws Exception {
                jdbc.update("UPDATE wallets SET balance = 1000000 WHERE id = ?", victimWallet);
                VictimState victimBefore = snapshot(victimWallet, victimTopUpRef);
                int victimTxBefore = txCountOfWallet(victimWallet);

                MvcResult res = postWithToken(WITHDRAW_URL, attackerTokens.access(),
                                "{\"amount\":500000,\"description\":\"steal\",\"sourceWalletId\":\""
                                                + victimWallet + "\"}");

                assertThat(statusOf(res)).as(body(res)).isEqualTo(400);
                assertThat(txCountOfWallet(victimWallet)).isEqualTo(victimTxBefore);
                assertThat(txCountOfWallet(attackerWallet)).isZero();
                assertThat(pendingOf(attackerWallet)).isZero();
                assertUnchanged(victimBefore, snapshot(victimWallet, victimTopUpRef));
        }

        @Test
        @DisplayName("IDOR: Chuyển tiền với body giả sourceWalletId của Victim sang ví khác => 400, Victim không mất tiền")
        void forgedSourceWalletOnTransfer_cannotStealFromVictim() throws Exception {
                jdbc.update("UPDATE wallets SET balance = 1000000 WHERE id = ?", victimWallet);
                VictimState victimBefore = snapshot(victimWallet, victimTopUpRef);
                int victimTxBefore = txCountOfWallet(victimWallet);

                MvcResult res = postWithToken(TRANSFER_URL, attackerTokens.access(),
                                "{\"amount\":500000,\"description\":\"steal\",\"destinationWalletId\":\""
                                                + receiverWallet + "\",\"sourceWalletId\":\"" + victimWallet
                                                + "\"}");

                assertThat(statusOf(res)).as(body(res)).isEqualTo(400);
                assertThat(txCountOfWallet(victimWallet)).isEqualTo(victimTxBefore);
                assertThat(balanceOf(receiverWallet)).isEqualByComparingTo("0");
                assertThat(balanceOf(attackerWallet)).isEqualByComparingTo("0");
                assertUnchanged(victimBefore, snapshot(victimWallet, victimTopUpRef));
        }

        @ParameterizedTest(name = "referenceId biến dạng = {0} => 400/403/404, không bao giờ 2xx hoặc 5xx")
        @ValueSource(strings = { "sql-injection", "wildcard-percent", "wildcard-star", "prefix-percent", "uppercase",
                        "trailing-space", "very-long", "redis-key-delimiter" })
        @DisplayName("IDOR: referenceId biến dạng (SQL injection, ký tự đại diện, hoa/thường, khoảng trắng, dấu ':') không bao giờ vượt được kiểm tra chủ sở hữu")
        void mutatedReferenceId_neverBypassesOwnership(String mutation) throws Exception {
                String mutated = mutate(mutation, victimTopUpRef);
                VictimState before = snapshot(victimWallet, victimTopUpRef);
                clearInvocations(emailService);

                for (String endpoint : VERIFY_ENDPOINTS) {
                        MvcResult res = callVerifyEndpoint(endpoint, attackerTokens.access(), mutated,
                                        secretFor(endpoint));
                        assertThat(statusOf(res)).as(endpoint + ": " + body(res)).isIn(400, 403, 404);
                }

                assertNoMailSent();
                assertUnchanged(before, snapshot(victimWallet, victimTopUpRef));
                assertThat(redisKeys(victimTopUpRef)).noneMatch(k -> k.endsWith(":" + ATTACKER));
        }

        @Test
        @DisplayName("IDOR Race: Victim và 7 request của Attacker cùng execute một token thật => Victim đúng 1 lần 200, Attacker toàn 403, tiền cộng 1 lần")
        void victimVsAttackers_execute_race() throws Exception {
                String otp = sendOtpAndCapture(VICTIM, victimTokens.access(), victimTopUpRef);
                String token = verifyOtpOk(victimTokens.access(), victimTopUpRef, otp);
                int pendingBefore = pendingOf(victimWallet);

                List<Outcome> outcomes = raceVictimAgainstAttackers(RACE_ATTACKERS,
                                () -> statusOf(executeRaw(victimTokens.access(), victimTopUpRef, token)),
                                () -> statusOf(executeRaw(attackerTokens.access(), victimTopUpRef, token)));

                assertRace(outcomes);
                assertThat(balanceOf(victimWallet)).isEqualByComparingTo("500000");
                assertThat(balanceOf(attackerWallet)).isEqualByComparingTo("0");
                assertThat(txStatus(victimTopUpRef)).isEqualTo("SUCCESS");
                assertThat(pendingOf(victimWallet)).isEqualTo(pendingBefore - 1);
        }

        @Test
        @DisplayName("IDOR Race: Victim và 7 request của Attacker cùng verify-otp bằng OTP thật => Victim nhận đúng 1 token, Attacker toàn 403, luồng execute còn nguyên")
        void victimVsAttackers_verifyOtp_race() throws Exception {
                String otp = sendOtpAndCapture(VICTIM, victimTokens.access(), victimTopUpRef);
                AtomicReference<String> victimToken = new AtomicReference<>();

                List<Outcome> outcomes = raceVictimAgainstAttackers(RACE_ATTACKERS,
                                () -> {
                                        MvcResult r = verifyOtpRaw(victimTokens.access(), victimTopUpRef, otp);
                                        if (statusOf(r) == 200) {
                                                victimToken.set(jsonString(r, "$.data"));
                                        }
                                        return statusOf(r);
                                },
                                () -> statusOf(verifyOtpRaw(attackerTokens.access(), victimTopUpRef, otp)));

                assertRace(outcomes);
                assertThat(victimToken.get()).isNotBlank();

                MvcResult exec = executeRaw(victimTokens.access(), victimTopUpRef, victimToken.get());
                assertThat(statusOf(exec)).as(body(exec)).isEqualTo(200);
                assertThat(balanceOf(victimWallet)).isEqualByComparingTo("500000");
                assertThat(redisKeys(victimTopUpRef)).noneMatch(k -> k.endsWith(":" + ATTACKER));
        }

        private MvcResult postWithToken(String url, String accessToken, String json) throws Exception {
                var req = post(url)
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON);
                if (json != null && !json.isEmpty()) {
                        req.content(json);
                }
                return mockMvc.perform(req).andReturn();
        }

        private String openWallet(Tokens tokens) throws Exception {
                assertThat(statusOf(postWithToken(OPEN_WALLET_URL, tokens.access(), ""))).isEqualTo(200);
                return jsonString(getWithToken(WALLET_URL, tokens.access()), "$.data.id");
        }

        private String createTopUp(Tokens tokens, long amount) throws Exception {
                MvcResult res = postWithToken(TOP_UP_URL, tokens.access(),
                                "{\"amount\":" + amount + ",\"description\":\"idor topup\"}");
                assertThat(statusOf(res)).as(body(res)).isEqualTo(200);
                return jsonString(res, "$.data.referenceId");
        }

        private String createTransfer(Tokens tokens, String destinationWalletId, long amount) throws Exception {
                MvcResult res = postWithToken(TRANSFER_URL, tokens.access(),
                                "{\"amount\":" + amount + ",\"description\":\"idor transfer\","
                                                + "\"destinationWalletId\":\"" + destinationWalletId + "\"}");
                assertThat(statusOf(res)).as(body(res)).isEqualTo(200);
                return jsonString(res, "$.data.referenceId");
        }

        private MvcResult sendOtpRaw(String accessToken, String refId) throws Exception {
                return postWithToken(SEND_OTP_URL, accessToken, "{\"referenceId\":\"" + refId + "\"}");
        }

        private MvcResult verifyOtpRaw(String accessToken, String refId, String otp) throws Exception {
                return postWithToken(VERIFY_OTP_URL, accessToken,
                                "{\"referenceId\":\"" + refId + "\",\"rawOtp\":\"" + otp + "\"}");
        }

        private MvcResult executeRaw(String accessToken, String refId, String rawToken) throws Exception {
                return postWithToken(EXECUTE_URL, accessToken,
                                "{\"referenceId\":\"" + refId + "\",\"rawToken\":\"" + rawToken + "\"}");
        }

        private String sendOtpAndCapture(String email, String accessToken, String refId) throws Exception {
                clearInvocations(emailService);
                MvcResult res = sendOtpRaw(accessToken, refId);
                assertThat(statusOf(res)).as(body(res)).isEqualTo(200);

                ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
                verify(emailService, timeout(5000))
                                .sendTransactionOtp(eq(email), otpCaptor.capture(), anyInt(), any(Transaction.class));
                return otpCaptor.getValue();
        }

        private String verifyOtpOk(String accessToken, String refId, String otp) throws Exception {
                MvcResult res = verifyOtpRaw(accessToken, refId, otp);
                assertThat(statusOf(res)).as(body(res)).isEqualTo(200);
                return jsonString(res, "$.data");
        }

        private MvcResult callVerifyEndpoint(String endpoint, String accessToken, String refId, String secret)
                        throws Exception {
                return switch (endpoint) {
                        case "send-otp" -> sendOtpRaw(accessToken, refId);
                        case "verify-otp" -> verifyOtpRaw(accessToken, refId, secret);
                        case "execute" -> executeRaw(accessToken, refId, secret);
                        default -> throw new IllegalArgumentException(endpoint);
                };
        }

        private static String secretFor(String endpoint) {
                return "verify-otp".equals(endpoint) ? "12345678" : "any-token";
        }

        private Tokens tokensOf(String actor) {
                return switch (actor) {
                        case "ATTACKER" -> attackerTokens;
                        case "ADMIN" -> adminTokens;
                        case "RECEIVER" -> receiverTokens;
                        default -> throw new IllegalArgumentException(actor);
                };
        }

        private static String emailOf(String actor) {
                return switch (actor) {
                        case "ATTACKER" -> ATTACKER;
                        case "ADMIN" -> ADMIN;
                        case "RECEIVER" -> RECEIVER;
                        default -> throw new IllegalArgumentException(actor);
                };
        }

        private static String mutate(String mutation, String ref) {
                return switch (mutation) {
                        case "sql-injection" -> ref + "' OR '1'='1";
                        case "wildcard-percent" -> "%";
                        case "wildcard-star" -> "*";
                        case "prefix-percent" -> ref.substring(0, 8) + "%";
                        case "uppercase" -> ref.toUpperCase();
                        case "trailing-space" -> ref + " ";
                        case "very-long" -> "a".repeat(1000);
                        case "redis-key-delimiter" -> ref + ":" + ATTACKER;
                        default -> throw new IllegalArgumentException(mutation);
                };
        }

        private record VictimState(BigDecimal balance, Integer pending, String status, Set<String> redisKeys) {
        }

        private VictimState snapshot(String walletId, String refId) {
                return new VictimState(balanceOf(walletId), pendingOf(walletId), txStatus(refId), redisKeys(refId));
        }

        private static void assertUnchanged(VictimState before, VictimState after) {
                assertThat(after.balance()).as("số dư").isEqualByComparingTo(before.balance());
                assertThat(after.pending()).as("pending").isEqualTo(before.pending());
                assertThat(after.status()).as("status giao dịch").isEqualTo(before.status());
                assertThat(after.redisKeys()).as("key Redis của giao dịch").isEqualTo(before.redisKeys());
        }

        private BigDecimal balanceOf(String walletId) {
                return jdbc.queryForObject("SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId);
        }

        private Integer pendingOf(String walletId) {
                return jdbc.queryForObject("SELECT pending FROM wallets WHERE id = ?", Integer.class, walletId);
        }

        private String txStatus(String refId) {
                return jdbc.queryForObject("SELECT status FROM transactions WHERE reference_id = ?", String.class,
                                refId);
        }

        private Integer txCountOfWallet(String walletId) {
                return jdbc.queryForObject(
                                "SELECT COUNT(*) FROM transactions WHERE source_wallet_id = ? OR destination_wallet_id = ?",
                                Integer.class, walletId, walletId);
        }

        private Set<String> redisKeys(String refId) {
                Set<String> keys = redis.keys("transaction:*:" + refId + ":*");
                return keys == null ? Set.of() : keys;
        }

        private void assertNoMailSent() {
                verify(emailService, after(300).never())
                                .sendTransactionOtp(anyString(), anyString(), anyInt(), any(Transaction.class));
        }

        private record Outcome(boolean victim, int status) {
        }

        private List<Outcome> raceVictimAgainstAttackers(int attackers, Callable<Integer> victimCall,
                        Callable<Integer> attackerCall) throws Exception {
                AtomicInteger ticket = new AtomicInteger();
                return runConcurrently(attackers + 1, () -> {
                        boolean isVictim = ticket.getAndIncrement() == 0;
                        return new Outcome(isVictim, isVictim ? victimCall.call() : attackerCall.call());
                });
        }

        private static void assertRace(List<Outcome> outcomes) {
                List<Integer> victim = outcomes.stream().filter(Outcome::victim).map(Outcome::status).toList();
                List<Integer> attackers = outcomes.stream().filter(o -> !o.victim()).map(Outcome::status).toList();
                assertThat(victim).containsExactly(200);
                assertThat(attackers).hasSize(RACE_ATTACKERS).containsOnly(403);
        }
}