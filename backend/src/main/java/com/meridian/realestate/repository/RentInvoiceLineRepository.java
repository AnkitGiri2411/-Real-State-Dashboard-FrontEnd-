package com.meridian.realestate.repository;

import com.meridian.realestate.domain.RentInvoiceLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The lines of a rent invoice.
 *
 * <p>The service compares {@code SUM(line_total)} here against the invoice's stored
 * {@code subtotal} inside one transaction - the schema spans rows and does not enforce it. A line
 * may be negative (a credit), so nothing here assumes positivity.</p>
 */
public interface RentInvoiceLineRepository extends JpaRepository<RentInvoiceLine, UUID> {

  Optional<RentInvoiceLine> findByIdAndDeletedAtIsNull(UUID id);

  Page<RentInvoiceLine> findAllByDeletedAtIsNull(Pageable pageable);

  /**
   * An invoice's lines in presentation order.
   *
   * <p>Ordered here because the document's line sequence is part of it - matching the
   * {@code (invoice_id, display_order)} index the schema ships.</p>
   */
  List<RentInvoiceLine> findAllByInvoice_IdAndDeletedAtIsNullOrderByDisplayOrderAsc(UUID invoiceId);

  long countByInvoice_IdAndDeletedAtIsNull(UUID invoiceId);
}
