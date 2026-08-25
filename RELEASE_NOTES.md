# Next AI Commerce 0.2.0

Released: 2026-08-25

Version 0.2 establishes the account/store operating model and the reusable UX foundation for commerce modules.

## Included

- Account-wide **All stores** context for aggregated Overview experiences
- Required store context for operational modules such as Orders and Inventory
- Searchable account and marketplace-store switcher with marketplace logos, flags, and readable account initials
- Compact grouped navigation for Sales, Catalog & Purchasing, Inventory, Growth & Recovery, Integrations, and Administration
- User-selectable light and dark appearance stored in the browser
- Responsive desktop and mobile layouts with Apple system typography
- Streamlined user invitations with all connected stores selected by default
- Verified email invitation and account activation workflow
- Compact sidebar identity, appearance, and sign-out controls
- Consistent NextAI favicon and updated visual hierarchy
- Initial platform information architecture for catalog, vendors, SKU mapping, receiving, inventory ledger, pricing, feedback, reimbursements, shipping, and Amazon Vendor

## Architecture decisions recorded for the next phase

- Global product identity is separate from account catalog records, vendor offers, and marketplace listings
- UPC/GTIN values are stored as text to retain leading zeros
- Vendor prices, discounts, case packs, minimum quantities, and effective dates belong to vendor offers
- Marketplace synchronization will use notification-first ingestion, incremental safety polling, and periodic reconciliation
- Operational jobs will be idempotent, tenant/store scoped, rate-limit aware, observable, and replayable
- Inventory changes will be recorded as immutable ledger movements rather than direct total replacements
- Local workstation printer profiles will separate packing-label and 4x6 shipping-label output

## Intentionally deferred

- Amazon and Walmart production synchronization jobs
- Global and account catalog database implementation
- Vendor catalog upload and mapping
- Marketplace SKU-to-product mapping
- Receiving, lot tracking, inventory ledger, and FEFO/FIFO allocation
- Shipping-label purchase and printer-agent integration
- AI-configurable Overview, alerts, and actions

## Verification and rollback

The release passed the complete automated suite: 20 tests with zero failures. The Git tag `v0.2.0` is the rollback point for this UX and access-control foundation.

---

# Next AI Commerce 0.1.0

Released: 2026-08-24

Version 0.1 establishes the secure multi-tenant foundation for Next AI Commerce.

## Included

- Business-account creation and switching for the Super Admin
- Tenant-scoped authentication, roles, memberships, and store permissions
- Invitation and one-time account activation foundations
- Amazon and Walmart marketplace connection workflows
- Amazon US, UK, and Canada marketplace selection with country flags
- Walmart US setup without Amazon-specific Seller ID requirements
- AES-256-GCM encrypted marketplace credentials
- Live Amazon and Walmart credential verification before activation
- Next AI Commerce branded, responsive user interface using Apple system fonts
- PostgreSQL migrations, row-level tenant boundaries, and automated security tests

## Intentionally deferred

- Amazon and Walmart order synchronization
- Inventory and profitability workflows
- Reimbursements
- Multi-distributor workflows
- FedEx and UPS label printing
- Dashboard analytics
- Production email delivery for invitations

## Verification

The release passed the complete automated test suite and user acceptance testing of sign-in, account creation, account switching, marketplace creation, and marketplace credential setup.
