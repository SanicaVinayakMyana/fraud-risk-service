package com.sanica.risk.domain;

/** How the money moved. Card payments and transfers are the channels fraud rules care most about. */
public enum Channel {
    CARD,
    TRANSFER,
    DEPOSIT,
    WITHDRAWAL
}
