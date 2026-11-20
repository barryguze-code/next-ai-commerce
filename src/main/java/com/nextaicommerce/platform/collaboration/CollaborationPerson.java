package com.nextaicommerce.platform.collaboration;

import java.util.UUID;

/** Identity metadata only for people on an already-authorized queue card. */
public record CollaborationPerson(UUID id,String displayName,boolean assigned,boolean participant) {}
