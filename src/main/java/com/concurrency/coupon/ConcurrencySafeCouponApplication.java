package com.concurrency.coupon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ConcurrencySafeCouponApplication {

	public static void main(String[] args) {
		SpringApplication.run(ConcurrencySafeCouponApplication.class, args);
	}

}
