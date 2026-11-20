# Amazon Buy Shipping implementation blueprint

Status: implemented through the resilient Shipping Desk in build `0.6.88-resilient-shipping-desk`  
Scope: Amazon seller-fulfilled orders, Merchant Fulfillment API v0, multi-tenant Java backend

## Executive decision

Build Buy Shipping as a store-scoped shipment subsystem beside the existing order and inventory models.
Do not add label fields directly to `amazon_orders`, do not store carrier credentials, and do not overwrite
customer-paid shipping revenue with postage expense.

The selected `marketplace_connection_id` is the security and rate-limit boundary. Its encrypted refresh token
produces a short-lived Login with Amazon access token. Amazon evaluates the carrier accounts and preferences
that the merchant linked in Seller Central; Next AI Commerce displays only the offers Amazon returns.

Merchant Fulfillment v0 rates one shipment request at a time. “Get rates for selected orders” is therefore a
Next AI Commerce queue that executes one `getEligibleShipmentServices` call per order/package, with a separate
token bucket per store and region. It is not one Amazon batch request.

Official contracts:

- [getEligibleShipmentServices](https://developer-docs.amazon.com/sp-api/reference/geteligibleshipmentservices)
- [createShipment](https://developer-docs.amazon.com/sp-api/reference/createshipment)
- [getShipment](https://developer-docs.amazon.com/sp-api/reference/getshipment)
- [cancelShipment](https://developer-docs.amazon.com/sp-api/reference/cancelshipment)
- [Merchant Fulfillment v0 OpenAPI model](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/merchant-fulfillment-api-model/merchantFulfillmentV0.json)
- [SP-API endpoints by selling region](https://developer-docs.amazon.com/sp-api/docs/sp-api-endpoints)
- [SP-API roles](https://developer-docs.amazon.com/sp-api/docs/direct-to-consumer-shipping-restricted-role)

## Implemented production boundaries

- Flyway `V53__amazon_buy_shipping.sql` owns ship-from addresses, package profiles and per-SKU defaults,
  rated offers, queued purchases/refunds, encrypted artifacts, item-level postage allocation, and audit events.
  Every new operational table has forced tenant row-level security.
- A connection starts in `RATES_ONLY`. Only an account administrator can enable `PURCHASE_ENABLED`, and the
  drawer still requires a second explicit charge confirmation for each package.
- `AmazonMerchantFulfillmentClient` selects NA/EU/FE from the order marketplace, fetches eligible services,
  buys the exact rated request, verifies Amazon's label checksum, decompresses it, and requests label refunds.
- `BuyShippingWorker` owns external mutations. A rejected request becomes `FAILED`; an interrupted or timed-out
  purchase becomes `PURCHASE_UNKNOWN` and the charge request is never retried automatically. When Amazon already
  returned a shipment ID but the label file failed, the worker retries only `getShipment` to recover that artifact.
  An abandoned in-progress purchase is also converted to `PURCHASE_UNKNOWN` after five minutes.
- The original carrier artifact remains archived. The printable copy is normalized to 4×6 and scaled into the
  upper label area so up to two compact packing rows can share the bottom of the first sheet. Both artifacts remain
  AES-GCM encrypted.
- Postage is an expense allocated across the shipment's order items. It never replaces the customer-paid shipping
  amount imported from Amazon.
- Buying or refunding a label does not change `amazon_orders.order_status` and does not release inventory. The
  established Amazon order synchronization remains authoritative for shipment and reservation lifecycle.
- Flyway `V54__shipping_desk_batches.sql` adds durable shipping batches, per-order progress, immutable policy
  snapshots, label access history, and package temperature class. Closing the browser or restarting the service
  cannot erase rate, purchase, recovery, or print state.
- The Shipping Desk admits only merchant-fulfilled Pending/Unshipped orders whose open items have one complete
  saved package profile. It orders expedited commitments first and groups identical SKU/quantity compositions
  together inside each priority group.
- Bulk rate selection is deterministic and review-only: refrigerated/frozen packages target two business days,
  configured blocked services (SurePost by default) are removed, and UPS Ground may win within the configured
  quality premium ($1 by default). The exact service, delivery estimate, planned handoff, extra-ice instruction,
  and total postage are shown before the operator confirms a charge.
- Each purchased combined PDF remains in the label library and in the order's shipment history. Opening a print
  file is recorded only as access—not claimed as proof that a physical printer succeeded—so it can always be
  opened again after paper or hardware failures.

## Shipping Desk UAT

1. Save a real ship-from address as default from one order's Buy Shipping drawer.
2. Save a package against the order SKU, including name, optional box tag, dimensions, weight, and the correct
   Ambient, Refrigerated, or Frozen storage type.
3. Open **Buy Shipping** in the Sales navigation. Verify only package-ready Pending/Unshipped MFN orders appear.
4. Select a small UAT group and choose **Prepare orders**. Leave the page and return while rates are prepared;
   verify the same batch and progress remain.
5. Review every recommendation. Confirm expedited orders are first, matching SKU compositions stay together,
   SurePost is absent, cold packages arrive within the target, and any UPS Ground premium is within policy.
6. Verify the handoff note: ordinary cold-chain Thursday/Friday work waits for Monday; paid or expedited cold work
   may hand off Friday and explicitly says **Extra ice**.
7. Enable the existing per-store UAT purchase switch, check the exact-total confirmation, and purchase the batch.
   Close and reopen the page during processing to verify recovery.
8. Print the batch PDF. Confirm label/packing pages follow the numbered sequence and packing details show package
   name, box tag, customer shipping, handoff note, location, quantity, and inventory expiration.
9. Reopen the batch with **Print again**, then reopen one label from **Purchased labels**. Neither action buys a
   second label. A refunded label remains recoverable but is visibly marked with its refund state.
10. Simulate a paper outage by closing the print dialog without printing. Return to the label library and reopen
    the same file; the platform must never describe opening the PDF as a successful physical print.

## UAT order

1. Confirm the SP-API application has the **Direct-to-Consumer Shipping** role, reauthorize the test seller after
   the role grant, and confirm carrier accounts and Buy Shipping terms in Seller Central.
2. Restart the application once so Flyway applies V53. Open a recent merchant-fulfilled `Pending` or `Unshipped`
   order and select the label icon in Action.
3. Add the warehouse ship-from address and leave “default” selected. Close and reopen the drawer; verify the address
   is brought back automatically.
4. Enter a real package's dimensions and weight. Turn on “Remember this package,” name the box, and compare rates.
   Reopen another order containing the same seller SKU and verify that package is prefilled.
5. While the store is in Safe preview mode, verify rate order, price, carrier/service, and delivery promise. The
   cheapest service should appear first and the fastest second when it is a different offer. No purchase is possible.
6. As an account administrator, select **Enable UAT purchasing**. Start with one low-risk, single-item test order,
   select Review, check the charge confirmation, and purchase. Wait for the drawer state to become Purchased.
7. Open Print and verify the Amazon carrier barcode and address remain sharp after the label is scaled into the
   upper part of the 4×6 sheet. With packing details enabled, verify the bottom strip includes quantity, brand when
   mapped, seller SKU, product name, storage location, and inventory expiration. Scan-test every UAT service.
8. Run the normal Amazon order sync. Verify Amazon—not the label worker—changes the order to Shipped and releases
   its inventory reservation.
9. On an unused UAT label, request **Refund label**. Verify the UI explicitly says this does not cancel the customer
   order and the shipment reaches Refund applied or a clearly explained review state.
10. Test a multi-quantity order: add a second package, split quantities without exceeding the unshipped total, rate
    and purchase each package, then verify separate tracking IDs and allocated postage.
11. Turn packing details off and buy another UAT label; the print file should contain only Amazon's carrier pages.
12. Finally test validation: zero dimension, over-assigned split quantity, expired rate, and a service that needs
    additional seller input. None may reach the purchase queue.

Do not test a forced network timeout against a valuable live order. If one occurs naturally and the drawer says
`Purchase unknown`, check Seller Central for a created shipment before any new rate or purchase attempt.

## 1. Prerequisites and tenant boundary

1. Obtain approval for the Direct-to-Consumer Shipping restricted role, add it to the application, and have
   every existing seller reauthorize. A refresh token issued before the new role is granted must not be assumed
   to carry the new authorization.
2. Keep the current encrypted `marketplace_connection_credentials` table. Never put a refresh token on an
   order, shipment, browser response, job payload, or log line.
3. Resolve the SP-API base URL from the marketplace region. The implemented client selects Amazon's NA, EU, or FE
   endpoint from the order marketplace while keeping credentials and request pacing scoped to the selected store.
4. Cache an LWA access token by `(tenant_id, marketplace_connection_id)` and refresh it before expiry. A store
   must never borrow another store's cached token or rate limiter.
5. Confirm that the merchant accepted Buy Shipping terms and linked/assigned carrier accounts in Seller Central.
   Amazon can return carriers whose terms are not accepted; the platform can explain this but cannot accept the
   merchant's legal terms on their behalf.
6. Use the current Orders dates for display and rating, but plan the separate migration from deprecated Orders
   API v0 to the current Orders API. Merchant Fulfillment itself remains v0; those version decisions are independent.

The selected order must be merchant fulfilled, `Pending` or `Unshipped`, have at least one unshipped item, and
belong to the selected store. Mapping and local reservation readiness are operational preconditions for picking,
but Amazon—not local inventory—decides carrier eligibility.

## 2. User workflow

### Orders table

The Action cell contains two deliberately different controls:

- **Mapping**: a compact link icon plus the stable word “Mapping.” It opens the existing catalogue mapping drawer.
  It never changes its verb to the ambiguous “Edit.” A mapped row uses the platform's quiet green treatment.
- **Buy Shipping Label**: a label/tag icon with the tooltip and accessible name “Buy Shipping Label.” It opens
  the implemented shipment drawer for eligible live orders; the server repeats eligibility checks before rating.

When enabled, Buy Shipping opens a right-side drawer instead of a modal over the table:

1. **Package** — preselect ship-from and a saved package profile. Show dimensions *and weight*. If missing,
   require both before rates can be requested. Carrier preference is optional and never hides other eligible rates.
2. **Rates** — show promise-compliant offers. Put a “Cheapest” card first and a “Fastest” card second. If one offer
   is both, show one card with both badges. The remaining offers sort by adjusted price, then latest delivery time.
3. **Review** — show carrier, service, ship date, delivery window, adjusted charge, package, items, and label format.
4. **Ready to print** — show tracking, Print 4×6, download, and label-refund actions. The Amazon order status remains
   read-only and is updated by the normal Amazon sync.

For a multi-quantity order, “Split shipment” lets the user assign unshipped quantities to packages. Every package
gets its own rate, purchased shipment, tracking number, label, and postage cost. Package item quantities must sum
to no more than the unshipped quantity and may not be negative or fractional.

### Date behavior

- Show Amazon's `latest_ship_date` as **Ship by** and `latest_delivery_date` as **Deliver by**.
- Send the planned handoff as `ShipDate` and Amazon's delivery promise as `MustArriveByDate`.
- Never silently relax the promise to make a cheap service appear. When no service meets it, show the rejected
  services/reasons and let the user correct package data or continue in Seller Central.
- Rank “fastest” by earliest `LatestEstimatedDeliveryDate`, then earliest estimated date, then adjusted cost.

## 3. Get eligible shipment services

Endpoint:

```http
POST /mfn/v0/eligibleShippingServices
x-amz-access-token: <store-scoped LWA access token>
Content-Type: application/json
```

Representative request:

```json
{
  "ShipmentRequestDetails": {
    "AmazonOrderId": "112-1234567-1234567",
    "ItemList": [
      {
        "OrderItemId": "52986411826454",
        "Quantity": 1,
        "ItemDescription": "Organic Peanut Butter"
      }
    ],
    "ShipFromAddress": {
      "Name": "Ibcore Shipping",
      "AddressLine1": "300 Turnbull Ave",
      "City": "Detroit",
      "StateOrProvinceCode": "MI",
      "PostalCode": "48123",
      "CountryCode": "US",
      "Phone": "3135550123",
      "Email": "shipping@example.com"
    },
    "PackageDimensions": {
      "Length": 10.25,
      "Width": 8.00,
      "Height": 4.00,
      "Unit": "inches"
    },
    "Weight": {
      "Value": 24.0,
      "Unit": "oz"
    },
    "ShipDate": "2026-09-05T17:30:00-07:00",
    "MustArriveByDate": "2026-09-09T23:59:59-07:00",
    "ShippingServiceOptions": {
      "DeliveryExperience": "DeliveryConfirmationWithoutSignature",
      "CarrierWillPickUp": false,
      "CarrierWillPickUpOption": "ShipperWillDropOff",
      "LabelFormat": "PDF"
    }
  },
  "ShippingOfferingFilter": {
    "IncludePackingSlipWithLabel": false,
    "IncludeComplexShippingOptions": true,
    "CarrierWillPickUp": "NoPreference",
    "DeliveryExperience": "NoPreference"
  }
}
```

Build `ItemList` from Amazon order item IDs and the quantity assigned to this package—not from the displayed SKU.
The destination is associated with the Amazon order; it is intentionally not accepted from browser input.

Persist every returned offer long enough to review it, including:

- `ShippingServiceId` and `ShippingServiceOfferId`
- carrier/service name
- `Rate` and `RateWithAdjustments`
- adjustment lines
- ship date and earliest/latest estimated delivery dates
- available label formats and format options
- `RequiresAdditionalSellerInputs` and benefits
- rejected services, temporarily unavailable carriers, and unaccepted-terms carriers

Use `RateWithAdjustments.Amount` for the UI comparison and eventual postage expense. Preserve the original rate
and adjustment lines for audit. A saved carrier preference may receive a small visual “Preferred” badge, but it
must not change the Cheapest or Fastest calculation.

If `RequiresAdditionalSellerInputs` is true, the current UI explains that the service must be completed in Seller
Central and disables its Purchase action. A later carrier-input phase can call the Merchant Fulfillment seller-input
operations and render Amazon's returned field definitions; it must not hard-code carrier-specific forms.

## 4. Purchase the selected shipment

Endpoint:

```http
POST /mfn/v0/shipments
x-amz-access-token: <same store's LWA access token>
Content-Type: application/json
```

The request must repeat the exact shipment details that were rated and include the selected service identifiers:

```json
{
  "ShipmentRequestDetails": {
    "AmazonOrderId": "112-1234567-1234567",
    "ItemList": [
      { "OrderItemId": "52986411826454", "Quantity": 1 }
    ],
    "ShipFromAddress": {
      "Name": "Ibcore Shipping",
      "AddressLine1": "300 Turnbull Ave",
      "City": "Detroit",
      "StateOrProvinceCode": "MI",
      "PostalCode": "48123",
      "CountryCode": "US",
      "Phone": "3135550123",
      "Email": "shipping@example.com"
    },
    "PackageDimensions": {
      "Length": 10.25,
      "Width": 8.00,
      "Height": 4.00,
      "Unit": "inches"
    },
    "Weight": { "Value": 24.0, "Unit": "oz" },
    "ShipDate": "2026-09-05T17:30:00-07:00",
    "MustArriveByDate": "2026-09-09T23:59:59-07:00",
    "ShippingServiceOptions": {
      "DeliveryExperience": "DeliveryConfirmationWithoutSignature",
      "CarrierWillPickUp": false,
      "CarrierWillPickUpOption": "ShipperWillDropOff",
      "LabelFormat": "PDF"
    }
  },
  "ShippingServiceId": "USPS_PTP_FC",
  "ShippingServiceOfferId": "offer-id-returned-by-amazon",
  "HazmatType": "None",
  "LabelFormatOption": {
    "IncludePackingSlipWithLabel": false,
    "LabelFormat": "PDF"
  }
}
```

For limited-quantity hazardous material, send the Amazon-supported `LQHazmat` value only after the product and
package passed the explicit hazmat workflow. Never infer hazmat from a product name.

On success, persist the Amazon `ShipmentId`, `TrackingId`, shipment status, selected service, rate with adjustments,
dates, and raw response. The label's `FileContents.Contents` is Base64-encoded GZIP data; decode, decompress, verify
the returned checksum, validate the advertised MIME type, and store an immutable artifact before reporting success.

Do not mark `amazon_orders.order_status` as shipped locally. Leave Amazon order status authoritative and let the
existing synchronization update it. Label purchase state and Amazon order state are separate facts.

## 5. PostgreSQL design

This schema extends the existing `marketplace_connections`, encrypted credentials, `amazon_orders`,
`amazon_order_items`, catalogue, warehouse locations, and reservation tables. It illustrates the required keys;
the production migration must apply the same row-level-security policy used by existing tenant-owned tables.

```sql
CREATE TABLE merchant_ship_from_addresses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    label VARCHAR(120) NOT NULL,
    contact_name VARCHAR(180) NOT NULL,
    company_name VARCHAR(180),
    address_line_1 VARCHAR(240) NOT NULL,
    address_line_2 VARCHAR(240),
    address_line_3 VARCHAR(240),
    city VARCHAR(120) NOT NULL,
    state_or_province_code VARCHAR(80),
    postal_code VARCHAR(32) NOT NULL,
    country_code CHAR(2) NOT NULL,
    phone VARCHAR(40) NOT NULL,
    email VARCHAR(320),
    is_default BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
      CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, marketplace_connection_id)
      REFERENCES marketplace_connections(tenant_id, id)
);
CREATE UNIQUE INDEX merchant_ship_from_one_default_idx
  ON merchant_ship_from_addresses(tenant_id, marketplace_connection_id)
  WHERE is_default AND status='ACTIVE';

CREATE TABLE shipping_package_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(120) NOT NULL,
    container_code VARCHAR(80),
    length NUMERIC(12,3) NOT NULL CHECK (length > 0),
    width NUMERIC(12,3) NOT NULL CHECK (width > 0),
    height NUMERIC(12,3) NOT NULL CHECK (height > 0),
    dimension_unit VARCHAR(12) NOT NULL CHECK (dimension_unit IN ('inches','centimeters')),
    weight NUMERIC(12,3) NOT NULL CHECK (weight > 0),
    weight_unit VARCHAR(12) NOT NULL CHECK (weight_unit IN ('oz','g')),
    preferred_carrier VARCHAR(120),
    preferred_service_id VARCHAR(160),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
      CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name)
);

CREATE TABLE marketplace_sku_package_defaults (
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    seller_sku VARCHAR(240) NOT NULL,
    package_profile_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, marketplace_connection_id, seller_sku),
    FOREIGN KEY (tenant_id, marketplace_connection_id)
      REFERENCES marketplace_connections(tenant_id, id),
    FOREIGN KEY (tenant_id, package_profile_id)
      REFERENCES shipping_package_profiles(tenant_id, id)
);

CREATE TABLE buy_shipping_shipments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    amazon_order_id VARCHAR(40) NOT NULL,
    ship_from_address_id UUID NOT NULL,
    package_profile_id UUID,
    state VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
      CHECK (state IN ('DRAFT','RATING','RATED','PURCHASE_IN_PROGRESS',
        'PURCHASED','PURCHASE_UNKNOWN','REFUND_PENDING','REFUND_REJECTED',
        'REFUND_APPLIED','FAILED')),
    request_fingerprint CHAR(64) NOT NULL,
    amazon_shipment_id VARCHAR(100),
    carrier_name VARCHAR(160),
    shipping_service_name VARCHAR(240),
    shipping_service_id VARCHAR(240),
    shipping_service_offer_id VARCHAR(240),
    tracking_id VARCHAR(240),
    rate_amount NUMERIC(19,4),
    adjusted_rate_amount NUMERIC(19,4),
    currency CHAR(3),
    label_format VARCHAR(40),
    ship_date TIMESTAMPTZ,
    earliest_delivery_date TIMESTAMPTZ,
    latest_delivery_date TIMESTAMPTZ,
    packing_slip_enabled BOOLEAN NOT NULL DEFAULT true,
    rating_expires_at TIMESTAMPTZ,
    purchase_started_at TIMESTAMPTZ,
    purchased_at TIMESTAMPTZ,
    raw_rate_response JSONB,
    raw_purchase_response JSONB,
    last_error_code VARCHAR(120),
    last_error_message VARCHAR(500),
    created_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, marketplace_connection_id, amazon_order_id)
      REFERENCES amazon_orders(tenant_id, marketplace_connection_id, amazon_order_id),
    FOREIGN KEY (tenant_id, ship_from_address_id)
      REFERENCES merchant_ship_from_addresses(tenant_id, id),
    FOREIGN KEY (tenant_id, package_profile_id)
      REFERENCES shipping_package_profiles(tenant_id, id)
);
CREATE UNIQUE INDEX buy_shipping_amazon_shipment_idx
  ON buy_shipping_shipments(tenant_id, marketplace_connection_id, amazon_shipment_id)
  WHERE amazon_shipment_id IS NOT NULL;

CREATE TABLE buy_shipping_shipment_items (
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    amazon_order_item_id VARCHAR(80) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    inventory_location_id UUID,
    inventory_expiration_date DATE,
    PRIMARY KEY (tenant_id, shipment_id, amazon_order_item_id),
    FOREIGN KEY (tenant_id, shipment_id)
      REFERENCES buy_shipping_shipments(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, marketplace_connection_id, amazon_order_item_id)
      REFERENCES amazon_order_items(tenant_id, marketplace_connection_id, amazon_order_item_id),
    FOREIGN KEY (tenant_id, inventory_location_id)
      REFERENCES warehouse_locations(tenant_id, id)
);

CREATE TABLE buy_shipping_rate_offers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    service_id VARCHAR(240) NOT NULL,
    offer_id VARCHAR(240),
    carrier_name VARCHAR(160) NOT NULL,
    service_name VARCHAR(240) NOT NULL,
    base_amount NUMERIC(19,4),
    adjusted_amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    earliest_delivery TIMESTAMPTZ,
    latest_delivery TIMESTAMPTZ,
    is_cheapest BOOLEAN NOT NULL DEFAULT false,
    is_fastest BOOLEAN NOT NULL DEFAULT false,
    requires_seller_input BOOLEAN NOT NULL DEFAULT false,
    payload JSONB NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, shipment_id)
      REFERENCES buy_shipping_shipments(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE shipping_label_artifacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    artifact_type VARCHAR(30) NOT NULL
      CHECK (artifact_type IN ('CARRIER_LABEL','PACKING_SLIP','COMPOSED_PRINT_FILE')),
    storage_key VARCHAR(500),
    encrypted_payload BYTEA,
    mime_type VARCHAR(100) NOT NULL,
    checksum_algorithm VARCHAR(20),
    checksum_value VARCHAR(180),
    width NUMERIC(10,3),
    height NUMERIC(10,3),
    dimension_unit VARCHAR(12),
    page_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    CHECK (storage_key IS NOT NULL OR encrypted_payload IS NOT NULL),
    FOREIGN KEY (tenant_id, shipment_id)
      REFERENCES buy_shipping_shipments(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE buy_shipping_cost_allocations (
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    amazon_order_item_id VARCHAR(80) NOT NULL,
    allocated_postage NUMERIC(19,4) NOT NULL CHECK (allocated_postage >= 0),
    currency CHAR(3) NOT NULL,
    PRIMARY KEY (tenant_id, shipment_id, amazon_order_item_id),
    FOREIGN KEY (tenant_id, shipment_id)
      REFERENCES buy_shipping_shipments(tenant_id, id) ON DELETE CASCADE
);
```

Prefer encrypted object storage for immutable label bytes and retain only a private storage key in PostgreSQL.
If the requirement is to keep Base64 in PostgreSQL, decode it first and encrypt the binary into `BYTEA`; do not
store a long-lived public label URL or duplicate the larger Base64 text. Generate short-lived download URLs only
after checking tenant and user permission.

Profit must calculate:

```text
item revenue + customer shipping revenue
- Amazon fees - cost of goods sold - adjusted purchased postage - other fulfillment costs
```

`amazon_order_items.shipping_price` remains customer revenue. Purchased postage comes from
`buy_shipping_shipments.adjusted_rate_amount`; split-package order cost is its sum. The allocation table supports
line-level profitability without losing the package-level carrier invoice truth.

## 6. Safe purchase and timeout recovery

`createShipment` is financially mutating and has no application idempotency key in its request. Treat it differently
from rate retrieval:

1. Lock the local shipment row and verify it is still `RATED`, its offer is not stale, and the order is still open.
2. Compare a SHA-256 request fingerprint covering store, order, items/quantities, ship-from, dimensions, weight,
   service/offer, dates, hazmat, and label format.
3. Commit `PURCHASE_IN_PROGRESS` before the network call. Only one worker may own the row.
4. On a clear 4xx rejection, record `FAILED` with a friendly correction message; do not retry unchanged input.
5. On success, store the Amazon shipment and verified artifact transactionally, then set `PURCHASED`.
6. On timeout or lost connection after submission, set `PURCHASE_UNKNOWN`. Never immediately call `createShipment`
   again, because the first call may have charged the seller.
7. If an Amazon shipment ID was received, reconcile it with `getShipment`. If no ID was received, surface “Purchase
   outcome needs review,” block repurchase, and direct an authorized operator to check Buy Shipping in Seller Central.
   Merchant Fulfillment v0 has no lookup-by-local-request-id operation that can prove the first call failed.

Use an outbox/job table so a browser timeout never controls purchase completion. The browser submits one local
command, receives a `202 Accepted` plus local shipment ID, and polls the local state. A worker owns the Amazon call.

### Error policy

| Condition | Platform behavior |
|---|---|
| Invalid dimensions/weight/address | Validate before Amazon; focus the exact field and keep the draft. |
| No delivery-window match | Preserve Amazon promise, show rejected reasons, allow package/date correction. |
| Hazmat or carrier seller input required | Disable that offer, explain why, and continue it in Seller Central; never guess. |
| `401` | Refresh the same store's LWA access token once, then require reconnection. |
| `403` | Explain missing role, seller reauthorization, Buy Shipping terms, or marketplace eligibility. |
| `429` | Honor `Retry-After`; use jittered backoff in that store's queue. |
| `500`/`503` during rates | Retry with exponential backoff and jitter within a short UI deadline. |
| timeout during purchase | Move to `PURCHASE_UNKNOWN`; do not blind-retry. |
| checksum/decompression failure | Keep Amazon shipment identity, quarantine artifact, refetch with `getShipment`. |
| carrier temporarily unavailable | Show it separately and leave available carriers selectable. |

Log store display name, short local operation ID, order ID, phase, elapsed time, outcome, Amazon request ID, and
retry time. Never log buyer address, label content, access/refresh token, or the full Amazon error body if it may
contain restricted data.

## 7. 4×6 label and packing-slip composition

Request PDF first. The implemented recovery path also converts a returned PNG to a 4×6 PDF; ZPL-only offers are
disabled because they cannot be safely composed or printed through the browser workflow. Amazon's label response
advertises format and physical dimensions, so do not assume every carrier returns the same canvas.

The internal packing strip follows the attached visual language:

- tenant brand at left; container/box badge at right
- marketplace SKU on one strong line
- up to two product rows per packing-slip page
- quantity in a bordered circle, followed by item code, short product name, expiration, and warehouse location
- a box/container tag only when the user selected or saved one
- later product rows continue on the next 4×6 page by default
- store-level “Print packing slip with label” preference, overridable for each purchase

The compositor creates a fresh 4×6 page, uniformly scales and centers Amazon's carrier PDF in the upper print area,
and draws the local packing strip below it without covering the carrier artwork. The first two product rows share
that sheet; later rows continue on a separate 4×6 packing page. The original Amazon artifact remains archived for
recovery. Because carrier barcode size and quiet zones are operationally critical, UAT must scan-test each enabled
carrier/service on the production printer before purchasing is enabled for that combination.

Amazon's `IncludePackingSlipWithLabel` can request Amazon's own packing slip, but it cannot produce this branded
warehouse layout. Keep that flag false when using the local compositor. Also do not rely on `CustomTextForLabel`:
the model limits it, format support is narrow, and some carriers do not support it.

## 8. Label refund versus order cancellation

These must never share one ambiguous “Cancel” button:

- **Refund shipping label** calls `DELETE /mfn/v0/shipments/{shipmentId}`. Amazon documents shipment states such
  as `RefundPending`, `RefundRejected`, and `RefundApplied`. The Amazon order can remain shipped, and cancelling the
  label does not cancel the customer's order. Poll `getShipment` until a terminal refund state.
- **Cancel Amazon order** is an order operation, not a Merchant Fulfillment operation. The Orders API is primarily
  retrieval/shipment confirmation and does not expose a simple seller-order cancel REST call. Amazon still lists
  order-acknowledgement feed types, but marketplace behavior and permitted cancellation reasons must be validated
  before Next AI Commerce submits one. Until that UAT and authorization gate passes, the safe action is “Open in
  Seller Central to cancel.” See Amazon's [official feed type list](https://developer-docs.amazon.com/sp-api/docs/feed-type-values).

Never release a local inventory reservation merely because a label refund was requested. Release it only when the
Amazon order synchronization reports the order canceled. If the order remains open, the stock remains reserved.

## 9. Java implementation shape

Extend the existing Java code rather than introducing a second Node service:

```text
AmazonMerchantFulfillmentClient
  quote(tenantId, connectionId, request)
  additionalSellerInputs(tenantId, connectionId, request)
  purchase(tenantId, connectionId, request)
  getShipment(tenantId, connectionId, amazonShipmentId)
  cancelShipment(tenantId, connectionId, amazonShipmentId)

BuyShippingService
  validates order/package, fingerprints requests, ranks offers, authorizes commands

BuyShippingWorker
  owns store-scoped rate limits, purchases, timeout recovery, and label persistence

BuyShippingRepository
  applies tenant context, row locks, state transitions, and cost allocations

ShippingLabelComposer
  verifies/decompresses Amazon files and produces the printable 4×6 document
```

Add `DELETE` support and Merchant Fulfillment-safe endpoint names to `AmazonSpApiClient`; make its regional base
URL connection-specific. Use typed request/response records rather than passing free-form strings beyond the client.
The same JSON and state model also work in Node.js, but splitting one fulfillment transaction across Java and Node
would create unnecessary idempotency and credential boundaries in the current application.

## 10. Implemented phases and acceptance gates

1. **Schema and permissions** — migration, RLS tests, encrypted artifact storage, and a friendly missing-role gate.
2. **Read-only rates** — request-contract fixtures are automated; one authorized live store remains the UAT gate.
3. **Purchase recovery** — row lock, outbox worker, checksum/decompression, timeout and duplicate-click tests.
4. **4×6 printing** — PDF and PNG handling, ZPL-only blocking, barcode-safe carrier pages, and two-SKU overflow tests.
5. **Split shipment and profit** — quantity constraints, multiple labels, postage sum and allocation tests.
6. **Refund label** — explicit wording, state polling, reservation remains intact.
7. **Order cancellation** — separate feed capability/UAT decision; otherwise Seller Central deep link only.
8. **Pilot rollout** — feature flag per `marketplace_connection_id`, first Ibcore or Karaca, audit every purchase,
   then enable the second store after charge/refund reconciliation.

Production enablement remains blocked until the restricted role is approved, each pilot seller has reauthorized,
Buy Shipping terms/carrier accounts are ready, and an uncertain-purchase runbook has been tested. Encrypted label
storage, the Orders UI, rate preview, purchase queue, recovery, printing, split costing, and refund polling are
implemented. Carrier-specific additional seller inputs and direct customer-order cancellation remain outside this
release: affected offers route to Seller Central, while Merchant Fulfillment `cancelShipment` is used only to request
a label refund.
