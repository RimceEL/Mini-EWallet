package com.backend.backend.global;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

public class DataNormalizerTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t", "\n", "\u3000" })
    @DisplayName("normalizeEmail: null, rỗng, toàn khoảng trắng => null")
    void normalizeEmail_blank_returnsNull(String input) {
        assertThat(DataNormalizer.normalizeEmail(input)).isNull();
    }

    @Test
    @DisplayName("normalizeEmail: bỏ khoảng trắng hai đầu (kể cả xuống dòng) và hạ chữ thường")
    void normalizeEmail_stripsAndLowercases() {
        assertThat(DataNormalizer.normalizeEmail("  AN@GMAIL.COM  ")).isEqualTo("an@gmail.com");
        assertThat(DataNormalizer.normalizeEmail("An@Gmail.com\n")).isEqualTo("an@gmail.com");
    }

    @Test
    @DisplayName("normalizeEmail: không đụng tới khoảng trắng ở giữa (để bước validate từ chối)")
    void normalizeEmail_keepsInnerSpaces() {
        assertThat(DataNormalizer.normalizeEmail("an @gmail.com")).isEqualTo("an @gmail.com");
    }

    @Test
    @DisplayName("normalizeEmail: không phụ thuộc locale máy (Thổ Nhĩ Kỳ: 'I' vẫn thành 'i')")
    void normalizeEmail_isLocaleIndependent() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(DataNormalizer.normalizeEmail("INFO@GMAIL.COM")).isEqualTo("info@gmail.com");
        } finally {
            Locale.setDefault(old);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t", "\n", "\u3000" })
    @DisplayName("normalizeText: null, rỗng, toàn khoảng trắng => null")
    void normalizeText_blank_returnsNull(String input) {
        assertThat(DataNormalizer.normalizeText(input)).isNull();
    }

    @Test
    @DisplayName("normalizeText: bỏ khoảng trắng hai đầu và gộp khoảng trắng liên tiếp ở giữa thành 1 dấu cách")
    void normalizeText_stripsAndCollapsesSpaces() {
        assertThat(DataNormalizer.normalizeText("  Nguyen   Van\tAn \n")).isEqualTo("Nguyen Van An");
    }

    @Test
    @DisplayName("normalizeText: giữ nguyên chữ có dấu tiếng Việt")
    void normalizeText_keepsVietnameseCharacters() {
        assertThat(DataNormalizer.normalizeText("  Nguyễn   Văn  An ")).isEqualTo("Nguyễn Văn An");
    }

    @Test
    @DisplayName("normalizeText: chuỗi đã sạch giữ nguyên, và chuẩn hoá hai lần cho cùng kết quả")
    void normalizeText_isIdempotent() {
        String once = DataNormalizer.normalizeText("  Tran   Thi  B ");

        assertThat(DataNormalizer.normalizeText("Tran Thi B")).isEqualTo("Tran Thi B");
        assertThat(DataNormalizer.normalizeText(once)).isEqualTo(once);
    }
}