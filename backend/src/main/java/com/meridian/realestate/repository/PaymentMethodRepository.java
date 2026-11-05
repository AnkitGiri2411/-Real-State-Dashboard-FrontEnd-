package com.meridian.realestate.repository;

import com.meridian.realestate.domain.PaymentMethod;
import com.meridian.realestate.domain.PaymentMethodCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lookup access to the payment methods.
 *
 * <p>Reference data: read-only in the application, and with no soft delete to filter on, so no
 * finder here carries a {@code deletedAt} condition.</p>
 */
public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {

  Optional<PaymentMethod> findByCode(String code);

  List<PaymentMethod> findAllByOrderByDisplayOrderAsc();

  /** The methods offered when recording a payment, in display order. */
  List<PaymentMethod> findAllByActiveTrueOrderByDisplayOrderAsc();

  List<PaymentMethod> findAllByCategory(PaymentMethodCategory category);
}
