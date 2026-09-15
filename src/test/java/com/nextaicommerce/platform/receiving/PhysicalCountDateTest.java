package com.nextaicommerce.platform.receiving;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PhysicalCountDateTest {
    @Test void acceptsAppSheetDatesWithExplicitTwentyFirstCenturyYears(){
        assertThat(PhysicalCountImportService.parseDate("5/16/27")).isEqualTo(LocalDate.of(2027,5,16));
        assertThat(PhysicalCountImportService.parseDate(" 10/2/26 ")).isEqualTo(LocalDate.of(2026,10,2));
        assertThat(PhysicalCountImportService.parseDate("01/20/27")).isEqualTo(LocalDate.of(2027,1,20));
        assertThat(PhysicalCountImportService.parseDate("2/29/00")).isEqualTo(LocalDate.of(2000,2,29));
        assertThat(PhysicalCountImportService.parseDate("12/31/99")).isEqualTo(LocalDate.of(2099,12,31));
    }
    @Test void preservesExistingFormats(){
        for(String date:new String[]{"2027-05-16","5/16/2027","5-16-2027"})
            assertThat(PhysicalCountImportService.parseDate(date)).isEqualTo(LocalDate.of(2027,5,16));
        assertThat(PhysicalCountImportService.parseDate("45000.0")).isEqualTo(LocalDate.of(1899,12,30).plusDays(45000));
    }
    @Test void rejectsImpossibleDatesInsteadOfSilentlyCorrectingThem(){
        for(String date:new String[]{"2/29/27","4/31/26","13/2/26","2/30/2027","2027-02-29","5/16/2"})
            assertThatThrownBy(()->PhysicalCountImportService.parseDate(date)).isInstanceOf(java.time.format.DateTimeParseException.class);
    }
}
