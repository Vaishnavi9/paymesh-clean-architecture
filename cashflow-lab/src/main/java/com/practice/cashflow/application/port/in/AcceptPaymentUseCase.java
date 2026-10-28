package com.practice.cashflow.application.port.in;

import com.practice.cashflow.application.AcceptPaymentCommand;
import com.practice.cashflow.application.PaymentAcceptance;

public interface AcceptPaymentUseCase {
    PaymentAcceptance accept(AcceptPaymentCommand command);
}
