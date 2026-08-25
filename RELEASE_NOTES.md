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
