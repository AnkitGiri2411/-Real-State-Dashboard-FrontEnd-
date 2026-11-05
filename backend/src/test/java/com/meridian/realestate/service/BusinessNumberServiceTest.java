package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.BusinessNumberSequence;
import com.meridian.realestate.repository.BusinessNumberSequenceRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Minting a readable number, with the repository mocked and no database.
 *
 * <p>What matters here is the shape of the number - its prefix and its padding, which a person reads
 * aloud - and that the counter is moved through the locking read and saved. The lock itself cannot be
 * asserted without a database; that it is <em>asked for</em> can be, and is: a refactor that swapped
 * {@code findByCodeForUpdate} for the unlocked {@code findByCode} would pass a live test suite only
 * until two people hit the same series at the same moment.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BusinessNumberService: minting a readable number")
class BusinessNumberServiceTest {

  @Mock private BusinessNumberSequenceRepository sequences;

  private BusinessNumberService service;

  @BeforeEach
  void setUp() {
    service = new BusinessNumberService(sequences);
  }

  /** A series row as the migration chain seeds it. */
  private static BusinessNumberSequence series(String code, String prefix, long nextValue, int padding) {
    BusinessNumberSequence sequence = new BusinessNumberSequence();
    sequence.setCode(code);
    sequence.setPrefix(prefix);
    sequence.setNextValue(nextValue);
    sequence.setPadding(padding);
    return sequence;
  }

  @Test
  @DisplayName("the number is the prefix plus the value padded to the series' width")
  void prefixThenZeroPadding() {
    BusinessNumberSequence property = series(BusinessNumberService.PROPERTY, "ME-P-", 16, 5);
    when(sequences.findByCodeForUpdate(BusinessNumberService.PROPERTY))
        .thenReturn(Optional.of(property));

    assertThat(service.next(BusinessNumberService.PROPERTY)).isEqualTo("ME-P-00016");
  }

  @Test
  @DisplayName("a small value is padded out to the full width")
  void smallValuePadsFully() {
    BusinessNumberSequence receipt = series(BusinessNumberService.RECEIPT, "ME-PAY-", 7, 6);
    when(sequences.findByCodeForUpdate(BusinessNumberService.RECEIPT))
        .thenReturn(Optional.of(receipt));

    assertThat(service.next(BusinessNumberService.RECEIPT)).isEqualTo("ME-PAY-000007");

    BusinessNumberSequence unit = series(BusinessNumberService.UNIT, "ME-U-", 1, 1);
    when(sequences.findByCodeForUpdate(BusinessNumberService.UNIT)).thenReturn(Optional.of(unit));

    assertThat(service.next(BusinessNumberService.UNIT)).isEqualTo("ME-U-1");
  }

  @Test
  @DisplayName("a value wider than the padding is printed in full, never truncated")
  void valueWiderThanPaddingIsNotTruncated() {
    BusinessNumberSequence lease = series(BusinessNumberService.LEASE, "ME-L-", 1234567, 5);
    when(sequences.findByCodeForUpdate(BusinessNumberService.LEASE))
        .thenReturn(Optional.of(lease));

    assertThat(service.next(BusinessNumberService.LEASE)).isEqualTo("ME-L-1234567");
  }

  @Test
  @DisplayName("a series that was never seeded is a configuration defect, not an API error")
  void missingSeriesThrows() {
    when(sequences.findByCodeForUpdate("NOPE")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.next("NOPE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOPE");

    verify(sequences, never()).save(any());
  }

  @Test
  @DisplayName("the counter is incremented and saved through the locking read")
  void incrementsAndSavesTheCounter() {
    BusinessNumberSequence tenant = series(BusinessNumberService.TENANT, "ME-T-", 12, 5);
    when(sequences.findByCodeForUpdate(BusinessNumberService.TENANT))
        .thenReturn(Optional.of(tenant));

    assertThat(service.next(BusinessNumberService.TENANT)).isEqualTo("ME-T-00012");

    assertThat(tenant.getNextValue()).isEqualTo(13);
    assertThat(tenant.getUpdatedAt()).isNotNull();
    verify(sequences).save(tenant);
    verify(sequences).findByCodeForUpdate(BusinessNumberService.TENANT);

    // The unlocked read is for display only; minting must never go through it.
    verify(sequences, never()).findByCode(anyString());
  }

  @Test
  @DisplayName("two calls in a row hand out two different numbers")
  void consecutiveCallsMoveOn() {
    BusinessNumberSequence sale = series(BusinessNumberService.SALE, "ME-S-", 40, 5);
    when(sequences.findByCodeForUpdate(BusinessNumberService.SALE)).thenReturn(Optional.of(sale));

    assertThat(service.next(BusinessNumberService.SALE)).isEqualTo("ME-S-00040");
    assertThat(service.next(BusinessNumberService.SALE)).isEqualTo("ME-S-00041");
  }

  @Test
  @DisplayName("the series codes are exactly the rows V2 seeds")
  void seriesCodesMatchTheSeededMigration() {
    assertThat(BusinessNumberService.PROPERTY).isEqualTo("PROPERTY");
    assertThat(BusinessNumberService.UNIT).isEqualTo("UNIT");
    assertThat(BusinessNumberService.OWNER).isEqualTo("OWNER");
    assertThat(BusinessNumberService.TENANT).isEqualTo("TENANT");
    assertThat(BusinessNumberService.LEAD).isEqualTo("LEAD");
    assertThat(BusinessNumberService.LISTING).isEqualTo("LISTING");
    assertThat(BusinessNumberService.VIEWING).isEqualTo("VIEWING");
    assertThat(BusinessNumberService.APPLICATION).isEqualTo("APPLICATION");
    assertThat(BusinessNumberService.LEASE).isEqualTo("LEASE");
    assertThat(BusinessNumberService.INVOICE).isEqualTo("INVOICE");
    assertThat(BusinessNumberService.RECEIPT).isEqualTo("RECEIPT");
    assertThat(BusinessNumberService.MAINTENANCE).isEqualTo("MAINTENANCE");
    assertThat(BusinessNumberService.SALE).isEqualTo("SALE");
  }
}
