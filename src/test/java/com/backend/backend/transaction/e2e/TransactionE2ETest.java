package com.backend.backend.transaction.e2e;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.security.SecurityIntegrationTestBase;
import com.backend.backend.transaction.Transaction;
import com.backend.backend.transaction.TransactionService;
import com.jayway.jsonpath.JsonPath;

public class TransactionE2ETest extends SecurityIntegrationTestBase {

        private static final String USER_A = "an@gmail.com";
        private static final String USER_B = "binh@gmail.com";

        private static final String BASE = "/api/v1/user/transaction";
        private static final String TOP_UP_URL = BASE + "/create/top-up";
        private static final String WITHDRAW_URL = BASE + "/create/withdraw";
        private static final String TRANSFER_URL = BASE + "/create/transfer";
        private static final String SEND_OTP_URL = BASE + "/verify/send-otp";
        private static final String VERIFY_OTP_URL = BASE + "/verify/verify-otp";
        private static final String EXECUTE_URL = BASE + "/execute";
        private static final String OPEN_WALLET_URL = "/api/v1/user/open-wallet";
        private static final String WALLET_URL = "/api/v1/user/wallet-details";

        private static final List<String> CREATE_URLS = List.of(TOP_UP_URL, WITHDRAW_URL, TRANSFER_URL);

        @Autowired
        private TransactionService transactionService;
        @Value("${app.transaction.max-otp-attempts:5}")
        private int maxOtpAttempts;
        @Value("${app.transaction.resend.max-attempts:5}")
        private int maxResendAttempts;

        private Tokens tokensA;
        private Tokens tokensB;
        private String walletA;
        private String walletB;

        @BeforeEach
        void setUpUsersAndWallets() throws Exception {
                createUser(USER_A, PASSWORD, Role.USER, 1, 1);
                createUser(USER_B, PASSWORD, Role.USER, 1, 1);

                tokensA = loginOk(USER_A, PASSWORD);
                tokensB = loginOk(USER_B, PASSWORD);

                walletA = openWallet(tokensA);
                walletB = openWallet(tokensB);
        }

        @Test
        @DisplayName("E2E: nạp tiền trọn luồng => số dư đúng, status SUCCESS, pending về 0, key Redis của giao dịch được dọn sạch")
        void e2e_topUpFullFlow_updatesBalanceAndCleansUp() throws Exception {
                String refId = createTopUp(tokensA, 500000);
                assertThat(pendingOf(walletA)).isEqualTo(1);
                assertThat(txStatus(refId)).isEqualTo("PENDING");

                MvcResult execRes = fullFlow(USER_A, tokensA, refId);

                assertThat(statusOf(execRes)).as(body(execRes)).isEqualTo(200);
                assertThat(jsonString(execRes, "$.data.status")).isEqualTo("SUCCESS");
                assertThat(jsonString(execRes, "$.data.referenceId")).isEqualTo(refId);
                assertThat(walletBalanceViaApi(tokensA.access())).isEqualByComparingTo("500000");
                assertThat(txStatus(refId)).isEqualTo("SUCCESS");
                assertThat(pendingOf(walletA)).isZero();
                assertThat(redisKeys(refId)).isEmpty();
        }

        @Test
        @DisplayName("E2E: rút tiền trọn luồng => số dư giảm đúng, pending về 0")
        void e2e_withdrawFullFlow_decreasesBalance() throws Exception {
                jdbc.update("UPDATE wallets SET balance = 1000000 WHERE id = ?", walletA);
                String refId = createWithdraw(tokensA, 400000);

                MvcResult execRes = fullFlow(USER_A, tokensA, refId);

                assertThat(statusOf(execRes)).as(body(execRes)).isEqualTo(200);
                assertThat(jsonString(execRes, "$.data.status")).isEqualTo("SUCCESS");
                assertThat(balanceOf(walletA)).isEqualByComparingTo("600000");
                assertThat(txStatus(refId)).isEqualTo("SUCCESS");
                assertThat(pendingOf(walletA)).isZero();
        }

        @Test
        @DisplayName("E2E: chuyển tiền giữa 2 user thật => trừ ở A, cộng đủ ở B, tổng tiền bảo toàn, pending của A về 0")
        void e2e_transferBetweenTwoUsers_movesMoney() throws Exception {
                jdbc.update("UPDATE wallets SET balance = 1000000 WHERE id = ?", walletA);
                String refId = createTransfer(tokensA, walletB, 350000);

                MvcResult execRes = fullFlow(USER_A, tokensA, refId);

                assertThat(statusOf(execRes)).as(body(execRes)).isEqualTo(200);
                assertThat(jsonString(execRes, "$.data.status")).isEqualTo("SUCCESS");
                assertThat(walletBalanceViaApi(tokensA.access())).isEqualByComparingTo("650000");
                assertThat(walletBalanceViaApi(tokensB.access())).isEqualByComparingTo("350000");
                assertThat(totalMoney()).isEqualByComparingTo("1000000");
                assertThat(pendingOf(walletA)).isZero();
                assertThat(pendingOf(walletB)).isZero();
        }

        @ParameterizedTest(name = "payload = {0}")
        @ValueSource(strings = {
                        "{\"amount\":1e400,\"description\":\"overflow\"}",
                        "{\"amount\":100.12345,\"description\":\"five decimals\"}",
                        "{\"amount\":1e-400,\"description\":\"tiny\"}",
                        "{\"amount\":-1000,\"description\":\"negative\"}",
                        "{\"amount\":0,\"description\":\"zero\"}",
                        "{\"amount\":10000000001,\"description\":\"over max\"}",
                        "{\"description\":\"missing amount\"}",
                        "{\"amount\":null,\"description\":\"null amount\"}",
                        "{\"amount\":\"abc\",\"description\":\"not a number\"}"
        })
        @DisplayName("E2E đầu vào độc hại: số tràn, thập phân, âm, 0, vượt trần, thiếu, null, không phải số => 400, không 500, không để lại pending hay bản ghi")
        void e2e_invalidAmountPayload_returns400_andLeavesNoResidue(String json) throws Exception {
                MvcResult res = postWithToken(TOP_UP_URL, tokensA.access(), json);

                assertThat(statusOf(res)).as(body(res)).isEqualTo(400);
                assertNoResidue(walletA);
        }

        @ParameterizedTest(name = "payload = {0}")
        @ValueSource(strings = {
                        "{\"amount\":10000000000,\"description\":\"dung tran\"}",
                        "{\"amount\":1,\"description\":\"nho nhat\"}",
                        "{\"amount\":100.0,\"description\":\"thap phan 0\"}",
                        "{\"amount\":100.0000,\"description\":\"bon so 0\"}",
                        "{\"amount\":1E+3,\"description\":\"dang mu\"}"
        })
        @DisplayName("E2E biên hợp lệ: đúng trần, nhỏ nhất, thập phân toàn số 0, dạng mũ => 200 và có đúng 1 pending")
        void e2e_boundaryAmountPayload_isAccepted(String json) throws Exception {
                MvcResult res = postWithToken(TOP_UP_URL, tokensA.access(), json);

                assertThat(statusOf(res)).as(body(res)).isEqualTo(200);
                assertThat(pendingOf(walletA)).isEqualTo(1);
                assertThat(txCount(walletA)).isEqualTo(1);
        }

        @ParameterizedTest(name = "body = [{0}]")
        @ValueSource(strings = { "", "{", "[]", "null", "{}", "\"abc\"" })
        @DisplayName("E2E body sai cấu trúc ở cả 3 API tạo giao dịch => lỗi 4xx, không bao giờ 500, không để lại bản ghi")
        void e2e_garbageBody_neverReturns5xx(String json) throws Exception {
                for (String url : CREATE_URLS) {
                        MvcResult res = postWithToken(url, tokensA.access(), json);
                        assertThat(statusOf(res)).as(url + ": " + body(res)).isBetween(400, 499);
                }
                assertNoResidue(walletA);
        }

        @ParameterizedTest(name = "ký tự = {0}")
        @ValueSource(strings = { "a", "ạ" })
        @DisplayName("E2E biên: description đúng 255 ký tự (kể cả ký tự có dấu) => 200 và lưu đủ 255 ký tự")
        void e2e_description255Chars_isAcceptedAndStoredInFull(String ch) throws Exception {
                String desc = ch.repeat(255);

                MvcResult res = postWithToken(TOP_UP_URL, tokensA.access(),
                                "{\"amount\":10000,\"description\":\"" + desc + "\"}");

                assertThat(statusOf(res)).as(body(res)).isEqualTo(200);
                String refId = jsonString(res, "$.data.referenceId");
                Integer stored = jdbc.queryForObject(
                                "SELECT CHAR_LENGTH(description) FROM transactions WHERE reference_id = ?",
                                Integer.class, refId);
                assertThat(stored).isEqualTo(255);
        }

        @ParameterizedTest(name = "độ dài description = {0}")
        @ValueSource(ints = { 256, 300, 5000 })
        @DisplayName("E2E đầu vào độc hại: description > 255 ký tự => 400, không 500, pending được rollback về 0")
        void e2e_descriptionOver255Chars_returns400_andRollsBackPending(int length) throws Exception {
                MvcResult res = postWithToken(TOP_UP_URL, tokensA.access(),
                                "{\"amount\":10000,\"description\":\"" + "a".repeat(length) + "\"}");

                assertThat(statusOf(res)).as(body(res)).isEqualTo(400);
                assertNoResidue(walletA);
        }

        @Test
        @DisplayName("E2E: description là tuỳ chọn, bỏ trống vẫn tạo được giao dịch")
        void e2e_missingDescription_isAccepted() throws Exception {
                MvcResult res = postWithToken(TOP_UP_URL, tokensA.access(), "{\"amount\":10000}");

                assertThat(statusOf(res)).as(body(res)).isEqualTo(200);
        }

        @Test
        @DisplayName("E2E: rút khi không đủ tiền trả 400 và không chiếm chỗ pending; đúng bằng số dư thì được")
        void e2e_withdrawInsufficientBalance_returns400_andExactBalanceIsAccepted() throws Exception {
                MvcResult empty = postWithToken(WITHDRAW_URL, tokensA.access(),
                                "{\"amount\":100000,\"description\":\"khong du tien\"}");
                assertThat(statusOf(empty)).isEqualTo(400);
                assertNoResidue(walletA);

                jdbc.update("UPDATE wallets SET balance = 50000 WHERE id = ?", walletA);
                MvcResult over = postWithToken(WITHDRAW_URL, tokensA.access(),
                                "{\"amount\":50001,\"description\":\"hon 1 dong\"}");
                assertThat(statusOf(over)).isEqualTo(400);
                assertNoResidue(walletA);

                MvcResult exact = postWithToken(WITHDRAW_URL, tokensA.access(),
                                "{\"amount\":50000,\"description\":\"dung so du\"}");
                assertThat(statusOf(exact)).as(body(exact)).isEqualTo(200);
                assertThat(pendingOf(walletA)).isEqualTo(1);
        }

        @ParameterizedTest(name = "tình huống = {0}")
        @ValueSource(strings = { "dest-not-exist", "self", "insufficient", "dest-locked", "dest-missing" })
        @DisplayName("E2E chuyển tiền không hợp lệ (ví đích không tồn tại, tự chuyển, thiếu tiền, ví đích bị khoá, thiếu ví đích) => 400, không để lại dấu vết")
        void e2e_invalidTransfer_returns400_andLeavesNoResidue(String scenario) throws Exception {
                jdbc.update("UPDATE wallets SET balance = 1000000 WHERE id = ?", walletA);
                String json = switch (scenario) {
                        case "dest-not-exist" -> transferJson(100000, UUID.randomUUID().toString());
                        case "self" -> transferJson(100000, walletA);
                        case "insufficient" -> transferJson(2000000, walletB);
                        case "dest-locked" -> {
                                jdbc.update("UPDATE wallets SET status = 'LOCKED' WHERE id = ?", walletB);
                                yield transferJson(100000, walletB);
                        }
                        case "dest-missing" -> "{\"amount\":100000,\"description\":\"thieu dich\"}";
                        default -> throw new IllegalArgumentException(scenario);
                };

                MvcResult res = postWithToken(TRANSFER_URL, tokensA.access(), json);

                assertThat(statusOf(res)).as(body(res)).isEqualTo(400);
                assertNoResidue(walletA);
                assertThat(balanceOf(walletA)).isEqualByComparingTo("1000000");
                assertThat(balanceOf(walletB)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("E2E: ví nguồn bị khoá => nạp, rút, chuyển đều 400 và không để lại dấu vết")
        void e2e_lockedSourceWallet_cannotCreateAnyTransaction() throws Exception {
                jdbc.update("UPDATE wallets SET balance = 1000000, status = 'LOCKED' WHERE id = ?", walletA);

                MvcResult topUp = postWithToken(TOP_UP_URL, tokensA.access(), "{\"amount\":10000}");
                MvcResult withdraw = postWithToken(WITHDRAW_URL, tokensA.access(), "{\"amount\":10000}");
                MvcResult transfer = postWithToken(TRANSFER_URL, tokensA.access(), transferJson(10000, walletB));

                assertThat(statusOf(topUp)).as(body(topUp)).isEqualTo(400);
                assertThat(statusOf(withdraw)).as(body(withdraw)).isEqualTo(400);
                assertThat(statusOf(transfer)).as(body(transfer)).isEqualTo(400);
                assertNoResidue(walletA);
        }

        @Test
        @DisplayName("E2E: user chưa mở ví tạo giao dịch => 404 ở cả 3 API")
        void e2e_userWithoutWallet_getsNotFound() throws Exception {
                createUser("chuamovi@gmail.com", PASSWORD, Role.USER, 1, 1);
                Tokens noWallet = loginOk("chuamovi@gmail.com", PASSWORD);

                List<String> bodies = List.of("{\"amount\":10000}", "{\"amount\":10000}",
                                transferJson(10000, walletB));
                for (int i = 0; i < CREATE_URLS.size(); i++) {
                        MvcResult res = postWithToken(CREATE_URLS.get(i), noWallet.access(), bodies.get(i));
                        assertThat(statusOf(res)).as(CREATE_URLS.get(i) + ": " + body(res)).isEqualTo(404);
                }
        }

        @Test
        @DisplayName("E2E: tạo quá 5 giao dịch PENDING trả 429, pending không vượt 5; user khác không bị ảnh hưởng; hoàn tất 1 giao dịch thì giải phóng 1 chỗ")
        void e2e_pendingLimitReturns429_andSlotIsReleasedAfterExecute() throws Exception {
                List<String> refs = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                        refs.add(createTopUp(tokensA, 10000));
                }

                MvcResult sixth = postWithToken(TOP_UP_URL, tokensA.access(),
                                "{\"amount\":10000,\"description\":\"tx-6\"}");
                assertThat(statusOf(sixth)).isEqualTo(429);
                assertThat(pendingOf(walletA)).isEqualTo(5);
                assertThat(txCount(walletA)).isEqualTo(5);

                createTopUp(tokensB, 10000);
                assertThat(pendingOf(walletB)).isEqualTo(1);

                MvcResult done = fullFlow(USER_A, tokensA, refs.get(0));
                assertThat(statusOf(done)).as(body(done)).isEqualTo(200);
                assertThat(pendingOf(walletA)).isEqualTo(4);

                createTopUp(tokensA, 10000);
                assertThat(pendingOf(walletA)).isEqualTo(5);
        }

        @Test
        @DisplayName("E2E Race: 10 request tạo giao dịch đồng thời trên một ví => đúng 5 thành công, 5 trả 429, pending = 5")
        void e2e_concurrentCreate_respectsPendingLimit() throws Exception {
                List<Integer> statuses = runConcurrently(10,
                                () -> statusOf(postWithToken(TOP_UP_URL, tokensA.access(),
                                                "{\"amount\":10000,\"description\":\"race create\"}")));

                assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(5);
                assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(5);
                assertThat(pendingOf(walletA)).isEqualTo(5);
                assertThat(txCount(walletA)).isEqualTo(5);
        }

        @Test
        @DisplayName("E2E: nhập sai OTP đủ ngưỡng => 400 từng lần, lần kế tiếp (dù đúng) 429 và OTP bị xoá; gửi lại OTP thì mở khoá")
        void e2e_wrongOtpLocksOut_andResendUnlocks() throws Exception {
                String refId = createTopUp(tokensA, 100000);
                String realOtp = sendOtpAndCapture(USER_A, tokensA.access(), refId);

                for (int i = 0; i < maxOtpAttempts; i++) {
                        MvcResult wrong = verifyOtpRaw(tokensA.access(), refId, "WRONG-OTP");
                        assertThat(statusOf(wrong)).as("lần sai " + i).isEqualTo(400);
                }
                MvcResult locked = verifyOtpRaw(tokensA.access(), refId, realOtp);
                assertThat(statusOf(locked)).as(body(locked)).isEqualTo(429);
                assertThat(redisKeys(refId)).noneMatch(k -> k.startsWith("transaction:otp:"));

                String newOtp = sendOtpAndCapture(USER_A, tokensA.access(), refId);
                String token = verifyOtpOk(tokensA.access(), refId, newOtp);
                MvcResult exec = executeRaw(tokensA.access(), refId, token);
                assertThat(statusOf(exec)).as(body(exec)).isEqualTo(200);
                assertThat(balanceOf(walletA)).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("E2E: gửi OTP vượt giới hạn gửi lại => 429 và không gửi thêm mail; giao dịch khác vẫn gửi được")
        void e2e_resendLimit_returns429_andOtherTransactionUnaffected() throws Exception {
                String refId = createTopUp(tokensA, 100000);
                String otherRef = createTopUp(tokensA, 100000);
                clearInvocations(emailService);

                for (int i = 0; i < maxResendAttempts; i++) {
                        MvcResult ok = sendOtpRaw(tokensA.access(), refId);
                        assertThat(statusOf(ok)).as("lần gửi " + i).isEqualTo(200);
                }
                MvcResult over = sendOtpRaw(tokensA.access(), refId);

                assertThat(statusOf(over)).as(body(over)).isEqualTo(429);
                verify(emailService, after(500).times(maxResendAttempts))
                                .sendTransactionOtp(anyString(), anyString(), anyInt(), any(Transaction.class));
                assertThat(txStatus(refId)).isEqualTo("PENDING");

                MvcResult other = sendOtpRaw(tokensA.access(), otherRef);
                assertThat(statusOf(other)).as(body(other)).isEqualTo(200);
        }

        @Test
        @DisplayName("E2E: gửi lại OTP làm OTP cũ mất hiệu lực, OTP mới dùng được")
        void e2e_resendInvalidatesPreviousOtp() throws Exception {
                String refId = createTopUp(tokensA, 100000);
                String oldOtp = sendOtpAndCapture(USER_A, tokensA.access(), refId);
                String newOtp = sendOtpAndCapture(USER_A, tokensA.access(), refId);
                Assumptions.assumeTrue(!oldOtp.equals(newOtp), "hai OTP ngẫu nhiên trùng nhau, bỏ qua");

                MvcResult oldRes = verifyOtpRaw(tokensA.access(), refId, oldOtp);
                assertThat(statusOf(oldRes)).as(body(oldRes)).isEqualTo(400);

                MvcResult newRes = verifyOtpRaw(tokensA.access(), refId, newOtp);
                assertThat(statusOf(newRes)).as(body(newRes)).isEqualTo(200);
        }

        @Test
        @DisplayName("E2E: OTP hết hạn (mất key Redis) => 400; gửi lại OTP thì hoàn tất được")
        void e2e_expiredOtp_returns400_andResendRecovers() throws Exception {
                String refId = createTopUp(tokensA, 100000);
                String otp = sendOtpAndCapture(USER_A, tokensA.access(), refId);
                deleteRedisKeys("transaction:otp:" + refId + ":*");

                MvcResult expired = verifyOtpRaw(tokensA.access(), refId, otp);
                assertThat(statusOf(expired)).as(body(expired)).isEqualTo(400);

                MvcResult exec = fullFlow(USER_A, tokensA, refId);
                assertThat(statusOf(exec)).as(body(exec)).isEqualTo(200);
                assertThat(balanceOf(walletA)).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("E2E: token hết hạn (mất key Redis) => execute 400, giao dịch vẫn PENDING; làm lại OTP thì hoàn tất được")
        void e2e_expiredToken_returns400_andFlowCanBeRedone() throws Exception {
                String refId = createTopUp(tokensA, 100000);
                String otp = sendOtpAndCapture(USER_A, tokensA.access(), refId);
                String token = verifyOtpOk(tokensA.access(), refId, otp);
                deleteRedisKeys("transaction:token:" + refId + ":*");

                MvcResult expired = executeRaw(tokensA.access(), refId, token);

                assertThat(statusOf(expired)).as(body(expired)).isEqualTo(400);
                assertThat(txStatus(refId)).isEqualTo("PENDING");
                assertThat(balanceOf(walletA)).isEqualByComparingTo("0");
                assertThat(pendingOf(walletA)).isEqualTo(1);

                MvcResult redo = fullFlow(USER_A, tokensA, refId);
                assertThat(statusOf(redo)).as(body(redo)).isEqualTo(200);
                assertThat(balanceOf(walletA)).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("E2E: bỏ qua bước: verify-otp khi chưa gửi OTP và execute khi chưa verify đều 400, giao dịch không đổi")
        void e2e_skippingSteps_isRejected() throws Exception {
                String refId = createTopUp(tokensA, 100000);

                MvcResult verifyFirst = verifyOtpRaw(tokensA.access(), refId, "12345678");
                MvcResult executeFirst = executeRaw(tokensA.access(), refId, "token-tu-che");

                assertThat(statusOf(verifyFirst)).as(body(verifyFirst)).isEqualTo(400);
                assertThat(statusOf(executeFirst)).as(body(executeFirst)).isEqualTo(400);
                assertThat(txStatus(refId)).isEqualTo("PENDING");
                assertThat(balanceOf(walletA)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("E2E: token của giao dịch 1 KHÔNG thể execute giao dịch 2, và không làm hỏng token của giao dịch 1")
        void e2e_tokenFromTx1_cannotExecuteTx2_andTx1StillWorks() throws Exception {
                String ref1 = createTopUp(tokensA, 100000);
                String ref2 = createTopUp(tokensA, 200000);
                String otp1 = sendOtpAndCapture(USER_A, tokensA.access(), ref1);
                String token1 = verifyOtpOk(tokensA.access(), ref1, otp1);

                MvcResult wrongExec = executeRaw(tokensA.access(), ref2, token1);

                assertThat(statusOf(wrongExec)).isEqualTo(400);
                assertThat(balanceOf(walletA)).isEqualByComparingTo("0");
                assertThat(txStatus(ref2)).isEqualTo("PENDING");

                MvcResult rightExec = executeRaw(tokensA.access(), ref1, token1);
                assertThat(statusOf(rightExec)).as(body(rightExec)).isEqualTo(200);
                assertThat(balanceOf(walletA)).isEqualByComparingTo("100000");
                assertThat(txStatus(ref2)).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("E2E: sau khi thành công, phát lại token, OTP hoặc gửi OTP lại đều 400 và tiền không đổi")
        void e2e_replayAfterSuccess_isRejected() throws Exception {
                String refId = createTopUp(tokensA, 250000);
                String otp = sendOtpAndCapture(USER_A, tokensA.access(), refId);
                String token = verifyOtpOk(tokensA.access(), refId, otp);
                assertThat(statusOf(executeRaw(tokensA.access(), refId, token))).isEqualTo(200);

                MvcResult replayExec = executeRaw(tokensA.access(), refId, token);
                MvcResult replayOtp = verifyOtpRaw(tokensA.access(), refId, otp);
                MvcResult replaySend = sendOtpRaw(tokensA.access(), refId);

                assertThat(statusOf(replayExec)).as(body(replayExec)).isEqualTo(400);
                assertThat(statusOf(replayOtp)).as(body(replayOtp)).isEqualTo(400);
                assertThat(statusOf(replaySend)).as(body(replaySend)).isEqualTo(400);
                assertThat(balanceOf(walletA)).isEqualByComparingTo("250000");
                assertThat(pendingOf(walletA)).isZero();
                assertThat(txStatus(refId)).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("E2E: 2 lệnh rút, mỗi lệnh bằng cả số dư => 1 thành công, lệnh kia 409 và rollback về PENDING, số dư không âm, token bị tiêu thụ")
        void e2e_twoWithdrawalsEachEqualToBalance_secondFailsAndRollsBack() throws Exception {
                jdbc.update("UPDATE wallets SET balance = 100000 WHERE id = ?", walletA);
                String ref1 = createWithdraw(tokensA, 100000);
                String ref2 = createWithdraw(tokensA, 100000);
                assertThat(pendingOf(walletA)).isEqualTo(2);

                MvcResult first = fullFlow(USER_A, tokensA, ref1);
                assertThat(statusOf(first)).as(body(first)).isEqualTo(200);

                String otp2 = sendOtpAndCapture(USER_A, tokensA.access(), ref2);
                String token2 = verifyOtpOk(tokensA.access(), ref2, otp2);
                MvcResult second = executeRaw(tokensA.access(), ref2, token2);

                assertThat(statusOf(second)).as(body(second)).isEqualTo(409);
                assertThat(balanceOf(walletA)).isEqualByComparingTo("0");
                assertThat(txStatus(ref1)).isEqualTo("SUCCESS");
                assertThat(txStatus(ref2)).isEqualTo("PENDING");
                assertThat(pendingOf(walletA)).isEqualTo(1);

                MvcResult replay = executeRaw(tokensA.access(), ref2, token2);
                assertThat(statusOf(replay)).as(body(replay)).isEqualTo(400);
        }

        @Test
        @DisplayName("E2E: ví bị khoá giữa lúc verify và execute => 409, rollback sạch; mở khoá rồi làm lại OTP thì thành công")
        void e2e_walletLockedBeforeExecute_rollsBack_andRecoversAfterUnlock() throws Exception {
                String refId = createTopUp(tokensA, 100000);
                String otp = sendOtpAndCapture(USER_A, tokensA.access(), refId);
                String token = verifyOtpOk(tokensA.access(), refId, otp);
                jdbc.update("UPDATE wallets SET status = 'LOCKED' WHERE id = ?", walletA);

                MvcResult res = executeRaw(tokensA.access(), refId, token);

                assertThat(statusOf(res)).as(body(res)).isEqualTo(409);
                assertThat(balanceOf(walletA)).isEqualByComparingTo("0");
                assertThat(txStatus(refId)).isEqualTo("PENDING");
                assertThat(pendingOf(walletA)).isEqualTo(1);

                jdbc.update("UPDATE wallets SET status = 'ACTIVE' WHERE id = ?", walletA);
                MvcResult redo = fullFlow(USER_A, tokensA, refId);
                assertThat(statusOf(redo)).as(body(redo)).isEqualTo(200);
                assertThat(balanceOf(walletA)).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("E2E: giao dịch quá hạn bị expire => execute 400, status FAILED, pending được giải phóng; giao dịch còn mới không bị ảnh hưởng")
        void e2e_expiredTransaction_cannotBeExecuted_andReleasesPending() throws Exception {
                String oldRef = createTopUp(tokensA, 100000);
                String youngRef = createTopUp(tokensA, 100000);
                String otp = sendOtpAndCapture(USER_A, tokensA.access(), oldRef);
                String token = verifyOtpOk(tokensA.access(), oldRef, otp);
                jdbc.update("UPDATE transactions SET created_at = NOW() - INTERVAL 31 MINUTE WHERE reference_id = ?",
                                oldRef);

                transactionService.expirePendingTransactions();

                assertThat(txStatus(oldRef)).isEqualTo("FAILED");
                assertThat(txStatus(youngRef)).isEqualTo("PENDING");
                assertThat(pendingOf(walletA)).isEqualTo(1);

                MvcResult exec = executeRaw(tokensA.access(), oldRef, token);
                MvcResult send = sendOtpRaw(tokensA.access(), oldRef);
                assertThat(statusOf(exec)).as(body(exec)).isEqualTo(400);
                assertThat(statusOf(send)).as(body(send)).isEqualTo(400);
                assertThat(balanceOf(walletA)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("E2E Race Condition: 8 request HTTP /execute bắn đồng thời cùng 1 token => đúng 1 trả 200, tiền cộng đúng 1 lần, pending giảm 1 lần")
        void e2e_concurrentExecuteSameToken_processesExactlyOnce() throws Exception {
                String refId = createTopUp(tokensA, 250000);
                String otp = sendOtpAndCapture(USER_A, tokensA.access(), refId);
                String txToken = verifyOtpOk(tokensA.access(), refId, otp);

                List<Integer> statuses = runConcurrently(8,
                                () -> statusOf(executeRaw(tokensA.access(), refId, txToken)));

                assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
                assertThat(statuses.stream().filter(s -> s == 400).count()).isEqualTo(7);
                assertThat(walletBalanceViaApi(tokensA.access())).isEqualByComparingTo("250000");
                assertThat(txStatus(refId)).isEqualTo("SUCCESS");
                assertThat(pendingOf(walletA)).isZero();
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
                return createOk(TOP_UP_URL, tokens, "{\"amount\":" + amount + ",\"description\":\"e2e topup\"}");
        }

        private String createWithdraw(Tokens tokens, long amount) throws Exception {
                return createOk(WITHDRAW_URL, tokens, "{\"amount\":" + amount + ",\"description\":\"e2e withdraw\"}");
        }

        private String createTransfer(Tokens tokens, String destinationWalletId, long amount) throws Exception {
                return createOk(TRANSFER_URL, tokens, transferJson(amount, destinationWalletId));
        }

        private String createOk(String url, Tokens tokens, String json) throws Exception {
                MvcResult res = postWithToken(url, tokens.access(), json);
                assertThat(statusOf(res)).as(body(res)).isEqualTo(200);
                return jsonString(res, "$.data.referenceId");
        }

        private static String transferJson(long amount, String destinationWalletId) {
                return "{\"amount\":" + amount + ",\"description\":\"e2e transfer\",\"destinationWalletId\":\""
                                + destinationWalletId + "\"}";
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

        private MvcResult fullFlow(String email, Tokens tokens, String refId) throws Exception {
                String otp = sendOtpAndCapture(email, tokens.access(), refId);
                String token = verifyOtpOk(tokens.access(), refId, otp);
                return executeRaw(tokens.access(), refId, token);
        }

        private BigDecimal walletBalanceViaApi(String accessToken) throws Exception {
                MvcResult res = getWithToken(WALLET_URL, accessToken);
                assertThat(statusOf(res)).isEqualTo(200);
                Number num = JsonPath.read(body(res), "$.data.balance");
                return new BigDecimal(num.toString());
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

        private Integer txCount(String walletId) {
                return jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE source_wallet_id = ?",
                                Integer.class, walletId);
        }

        private BigDecimal totalMoney() {
                return jdbc.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM wallets", BigDecimal.class);
        }

        private void assertNoResidue(String walletId) {
                assertThat(pendingOf(walletId)).as("pending").isZero();
                assertThat(txCount(walletId)).as("số giao dịch").isZero();
        }

        private Set<String> redisKeys(String refId) {
                Set<String> keys = redis.keys("transaction:*:" + refId + ":*");
                return keys == null ? Set.of() : keys;
        }

        private void deleteRedisKeys(String pattern) {
                Set<String> keys = redis.keys(pattern);
                assertThat(keys).as("key Redis theo mẫu " + pattern).isNotEmpty();
                redis.delete(keys);
        }
}