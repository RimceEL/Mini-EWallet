package com.backend.backend.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import com.backend.backend.wallet.customexception.UserNotFoundException;
import com.backend.backend.wallet.customexception.WalletNotFoundException;
import com.backend.backend.wallet.customexception.WalletOpenedException;

@ExtendWith(MockitoExtension.class)
public class WalletServiceTest {

        private static final String EMAIL = "an@gmail.com";

        @Mock
        private WalletRepository walletRepository;

        @InjectMocks
        private WalletService walletService;

        private static Wallet sampleWallet() {
                LocalDateTime now = LocalDateTime.of(2026, 11, 21, 10, 0);
                return new Wallet(
                                "wallet-001",
                                "user-001",
                                new BigDecimal("500000.0000"),
                                "VND",
                                "ACTIVE",
                                now,
                                now,
                                0);
        }

        @Test
        @DisplayName("openWallet: email hợp lệ và chèn được 1 dòng => không ném lỗi")
        void openWallet_whenValidEmail_shouldDelegateToRepository() {
                when(walletRepository.createWalletByUserEmail(EMAIL)).thenReturn(1);

                assertThatCode(() -> walletService.openWallet(EMAIL))
                                .doesNotThrowAnyException();

                verify(walletRepository).createWalletByUserEmail(EMAIL);
                verifyNoMoreInteractions(walletRepository);
        }

        @Test
        @DisplayName("openWallet: repository trả về 0 dòng (user không tồn tại) => ném UserNotFoundException")
        void openWallet_whenZeroRowsInserted_shouldThrowUserNotFoundException() {
                when(walletRepository.createWalletByUserEmail(EMAIL)).thenReturn(0);

                assertThatThrownBy(() -> walletService.openWallet(EMAIL))
                                .isInstanceOf(UserNotFoundException.class)
                                .hasMessage("User is not existed");

                verify(walletRepository).createWalletByUserEmail(EMAIL);
                verifyNoMoreInteractions(walletRepository);
        }

        @ParameterizedTest(name = "email = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = { "   ", "\t" })
        @DisplayName("[GIẢ THUYẾT] openWallet: email null hoặc rỗng => ném UserNotFoundException và không gọi DB")
        void openWallet_whenEmailIsNullOrBlank_shouldThrowUserNotFoundException(String invalidEmail) {
                assertThatThrownBy(() -> walletService.openWallet(invalidEmail))
                                .isInstanceOf(UserNotFoundException.class);

                verifyNoMoreInteractions(walletRepository);
        }

        @Test
        @DisplayName("openWallet: user đã có ví (DuplicateKeyException) => ném WalletOpenedException")
        void openWallet_whenWalletAlreadyExists_shouldThrowWalletOpenedException() {
                when(walletRepository.createWalletByUserEmail(EMAIL))
                                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'wallets.user_id'"));

                assertThatThrownBy(() -> walletService.openWallet(EMAIL))
                                .isInstanceOf(WalletOpenedException.class)
                                .hasMessage("This user has already open wallet");

                verify(walletRepository).createWalletByUserEmail(EMAIL);
                verifyNoMoreInteractions(walletRepository);
        }

        @Test
        @DisplayName("openWallet: lỗi kết nối DB (DataAccessException khác) => lan truyền nguyên gốc, không nuốt thành lỗi nghiệp vụ")
        void openWallet_whenUnexpectedDataAccessExceptionOccurs_shouldPropagate() {
                when(walletRepository.createWalletByUserEmail(EMAIL))
                                .thenThrow(new DataAccessResourceFailureException("MySQL connection lost"));

                assertThatThrownBy(() -> walletService.openWallet(EMAIL))
                                .isInstanceOf(DataAccessResourceFailureException.class);

                verify(walletRepository).createWalletByUserEmail(EMAIL);
                verifyNoMoreInteractions(walletRepository);
        }

        @Test
        @DisplayName("getWalletDetails: ví tồn tại => trả về đúng đối tượng Wallet")
        void getWalletDetails_whenWalletExists_shouldReturnWallet() {
                Wallet expected = sampleWallet();
                when(walletRepository.findWalletByUserEmail(EMAIL)).thenReturn(Optional.of(expected));

                Wallet actual = walletService.getWalletDetails(EMAIL);

                assertThat(actual).isSameAs(expected);
                verify(walletRepository).findWalletByUserEmail(EMAIL);
                verifyNoMoreInteractions(walletRepository);
        }

        @Test
        @DisplayName("getWalletDetails: không tìm thấy ví hoặc email => ném WalletNotFoundException")
        void getWalletDetails_whenWalletNotFound_shouldThrowWalletNotFoundException() {
                when(walletRepository.findWalletByUserEmail(EMAIL)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> walletService.getWalletDetails(EMAIL))
                                .isInstanceOf(WalletNotFoundException.class)
                                .hasMessage("Email is not existed or User has not opened wallet yet");

                verify(walletRepository).findWalletByUserEmail(EMAIL);
                verifyNoMoreInteractions(walletRepository);
        }

        @Test
        @DisplayName("getWalletDetails: lỗi DB => lan truyền nguyên gốc")
        void getWalletDetails_whenRepositoryThrowsDataAccessException_shouldPropagate() {
                when(walletRepository.findWalletByUserEmail(EMAIL))
                                .thenThrow(new DataAccessResourceFailureException("MySQL connection lost"));

                assertThatThrownBy(() -> walletService.getWalletDetails(EMAIL))
                                .isInstanceOf(DataAccessResourceFailureException.class);

                verify(walletRepository).findWalletByUserEmail(EMAIL);
                verifyNoMoreInteractions(walletRepository);
        }
}
