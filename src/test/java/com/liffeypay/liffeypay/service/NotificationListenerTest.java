package com.liffeypay.liffeypay.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationListenerTest {

    @Mock NotificationService notificationService;
    @InjectMocks NotificationListener notificationListener;

    @Test
    void onTransferCompleted_callsNotifyWithEvent() {
        TransferCompletedEvent event = new TransferCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("100.0000"), "EUR"
        );

        notificationListener.onTransferCompleted(event);

        verify(notificationService).notify(event);
    }
}
