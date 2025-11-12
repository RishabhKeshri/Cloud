//Circuit Breaker 
/*Scenario

An OrderService calls a PaymentService that may fail under heavy load.
We use Resilience4j CircuitBreaker to stop repeated failing calls.*/
//Dependenices

<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>

//Payment Service port 8082
package com.example.payment;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import java.util.Random;

@SpringBootApplication
@RestController
public class PaymentApp {
    Random random = new Random();
    public static void main(String[] args) { SpringApplication.run(PaymentApp.class, args); }

    @GetMapping("/payment/process")
    public String processPayment() {
        if (random.nextInt(10) < 6) throw new RuntimeException("Payment Service Down");
        return "✅ Payment Processed Successfully";
    }
}


//Order Service
package com.example.order;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@SpringBootApplication
@RestController
@RequestMapping("/order")
public class OrderApp {
    RestTemplate rest = new RestTemplate();
    public static void main(String[] args) { SpringApplication.run(OrderApp.class, args); }

    @GetMapping("/place")
    @CircuitBreaker(name = "paymentCB", fallbackMethod = "fallback")
    public String placeOrder() {
        String response = rest.getForObject("http://localhost:8082/payment/process", String.class);
        return "🛒 Order Confirmed → " + response;
    }

    public String fallback(Exception e) {
        return "⚡ Payment temporarily unavailable, order saved for retry.";
    }
}


//Retry Pattern
/*Scenario A StockService occasionally times out due to network glitches.
The ProductService retries automatically before giving up.*/
//Dependenices
<dependency>
  <groupId>org.springframework.retry</groupId>
  <artifactId>spring-retry</artifactId>
</dependency>

//StockService (port 8083)
package com.example.stock;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import java.util.Random;

@SpringBootApplication
@RestController
public class StockApp {
    Random random = new Random();
    public static void main(String[] args){ SpringApplication.run(StockApp.class,args); }

    @GetMapping("/stock/check")
    String checkStock() {
        if (random.nextBoolean()) throw new RuntimeException("Stock Service Timeout");
        return "✅ Stock Available (42 units)";
    }
}


//ProductService (port 8084)
package com.example.product;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.retry.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@EnableRetry
@RestController
@RequestMapping("/product")
public class ProductApp {
    RestTemplate rest = new RestTemplate();
    public static void main(String[] args){ SpringApplication.run(ProductApp.class,args); }

    @GetMapping("/fetch")
    @Retryable(value = RuntimeException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    String fetchProduct() {
        return "📦 Product Details → " + rest.getForObject("http://localhost:8083/stock/check", String.class);
    }

    @Recover
    String recover(RuntimeException e) {
        return "🚫 Stock service unreachable, please try later.";
    }
}




//Chained Service
//Booking involves three dependent services — Booking → Payment → Notification.

BookingService (port 8085)
package com.example.booking;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@RestController
@RequestMapping("/booking")
public class BookingApp {
    RestTemplate rest = new RestTemplate();
    public static void main(String[] args){ SpringApplication.run(BookingApp.class,args); }

    @GetMapping("/create")
    String createBooking() {
        String payment = rest.getForObject("http://localhost:8086/payment/pay", String.class);
        String notify = rest.getForObject("http://localhost:8087/notify/send", String.class);
        return "🎟️ Booking Created → " + payment + " → " + notify;
    }
}


//PaymentService (port 8086)
package com.example.payment;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@RestController
public class PaymentApp {
    public static void main(String[] args){ SpringApplication.run(PaymentApp.class,args); }

    @GetMapping("/payment/pay")
    String pay() { return "💳 Payment Successful"; }
}

//NotificationService (port 8087)
package com.example.notify;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@RestController
public class NotificationApp {
    public static void main(String[] args){ SpringApplication.run(NotificationApp.class,args); }

    @GetMapping("/notify/send")
    String send(){ return "📩 Email Confirmation Sent"; }
}


//Aggregator Pattern
//A DashboardService fetches info from multiple microservices and merges results.

//Dependencies

//DashboardService (port 8088)
package com.example.dashboard;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

@SpringBootApplication
@RestController
@RequestMapping("/dashboard")
public class DashboardApp {
    WebClient client = WebClient.create();
    public static void main(String[] args){ SpringApplication.run(DashboardApp.class,args); }

    @GetMapping("/summary")
    Mono<String> summary() {
        Mono<String> orders = client.get().uri("http://localhost:8081/order/place").retrieve().bodyToMono(String.class);
        Mono<String> stock = client.get().uri("http://localhost:8083/stock/check").retrieve().bodyToMono(String.class);

        return Mono.zip(orders, stock, (o, s) -> "📊 Dashboard → " + o + " | " + s);
    }
}


//Rate Limiting Pattern
//An API Gateway limits excessive user requests (using a simple in-memory limiter).
//Gateway (port 8089)
package com.example.gateway;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@SpringBootApplication
@RestController
public class GatewayApp {
    Map<String, Integer> requestCount = new HashMap<>();
    Map<String, Long> timestamp = new HashMap<>();
    static final int LIMIT = 3, WINDOW_MS = 5000;

    public static void main(String[] args){ SpringApplication.run(GatewayApp.class,args); }

    @GetMapping("/api/{user}")
    synchronized String handle(@PathVariable String user){
        long now = System.currentTimeMillis();
        timestamp.putIfAbsent(user, now);
        if (now - timestamp.get(user) > WINDOW_MS) {
            timestamp.put(user, now);
            requestCount.put(user, 0);
        }
        requestCount.put(user, requestCount.getOrDefault(user, 0) + 1);
        return (requestCount.get(user) > LIMIT) ?
            "🚫 Too Many Requests, please wait." : "✅ Request Served for " + user;
    }
}


//Combined Example (Retry + Circuit Breaker)
//An OrderService calls unstable PaymentService and uses both retry + circuit breaker.
//OrderService (port 8090)

package com.example.order;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.retry.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@SpringBootApplication
@EnableRetry
@RestController
@RequestMapping("/order")
public class OrderApp {
    RestTemplate rest = new RestTemplate();
    public static void main(String[] args){ SpringApplication.run(OrderApp.class,args); }

    @GetMapping("/process")
    @Retryable(value=RuntimeException.class, maxAttempts=3, backoff=@Backoff(delay=1000))
    @CircuitBreaker(name="paymentCB", fallbackMethod="fallback")
    String processOrder() {
        return "🛍️ Order → " + rest.getForObject("http://localhost:8082/payment/process", String.class);
    }

    String fallback(Exception e){ return "⚡ Payment service down, order queued."; }
}


/*🧱 1️⃣ Circuit Breaker Pattern
🔹 Definition

The Circuit Breaker Pattern is a fault-tolerance design that prevents a service from trying to repeatedly call another failing service.
It "trips" open after a threshold of failures and temporarily blocks further calls.

🔹 Problem It Solves

In distributed systems, if one microservice (say, Payment) is down or slow, constant retries from another service (like Order) can:

Waste resources

Cause cascading failures

Increase latency

🔹 Key Idea

Like an electrical circuit breaker, it has 3 states:

Closed: Calls flow normally

Open: Calls are blocked immediately (to avoid failure storm)

Half-Open: A few trial requests are allowed to test if recovery happened

If a call succeeds → circuit closes again.

🔹 When to Use

When a service depends on remote services/APIs that can fail or timeout (like payments, 3rd-party APIs).

🔹 Example Scenario

OrderService → PaymentService

After 5 failures, the circuit opens.

Calls are skipped for 30 seconds.

Then a test request is allowed (half-open).

🔹 Common Tools

Resilience4j (modern, lightweight)

Netflix Hystrix (legacy)

Spring Cloud Circuit Breaker

🔁 2️⃣ Retry Pattern
🔹 Definition

The Retry Pattern automatically re-executes a failed operation, usually after a delay, to recover from transient (temporary) errors.

🔹 Problem It Solves

Transient issues like:

Network glitches

Temporary API downtime

Database lock timeouts

...can cause temporary failures that succeed if retried after a short wait.

🔹 Key Idea

Automatically reattempt the operation n times with optional delay or exponential backoff.

🔹 When to Use

When failures are temporary and not systemic, e.g.:

Calling remote APIs

Writing to databases or queues

🔹 Example Scenario

Payment API fails randomly due to network issues.

OrderService retries the call 3 times with 1-second gaps before giving up.

🔹 Common Tools

Spring Retry

Resilience4j Retry

Polly (.NET)

🔗 3️⃣ Chained Service Pattern (Service Composition)
🔹 Definition

In this pattern, multiple services call each other sequentially, passing results along the chain to complete a single business process.

🔹 Problem It Solves

When one workflow logically requires multiple sequential steps handled by different services, chaining allows orchestration across them.

🔹 Key Idea

Service A → Service B → Service C
Each service handles one part and calls the next.

🔹 When to Use

When you need a synchronous workflow across services:

Each service must complete before the next one starts.

For example: booking → payment → notification.

🔹 Example Scenario

BookingService calls PaymentService

After successful payment, it calls NotificationService

🔹 Common Tools

REST API calls (RestTemplate, WebClient)

gRPC (for low-latency chaining)

Orchestration via Camunda / Temporal / Zeebe (advanced)

🧩 4️⃣ Aggregator Pattern
🔹 Definition

The Aggregator Pattern collects data from multiple microservices and combines them into one composite response for the client.

🔹 Problem It Solves

Without an aggregator, the client would need to make multiple API calls, increasing:

Latency

Network overhead

Client-side complexity

🔹 Key Idea

Aggregator (or API Gateway) acts as an orchestrator, calling multiple microservices in parallel and merging their responses.

🔹 When to Use

When a single screen (e.g., dashboard or product page) requires data from multiple microservices.

🔹 Example Scenario

E-commerce “Product Details Page”:

ProductService → product info

InventoryService → stock count

ReviewService → ratings
Aggregator merges all three and returns a single JSON.

🔹 Common Tools

Spring WebFlux (Mono.zip) for async aggregation

GraphQL (acts like an aggregator for queries)

API Gateway (with composite responses)

🚦 5️⃣ Rate Limiting Pattern
🔹 Definition

Rate Limiting controls how many requests a client or user can make within a given time window.

🔹 Problem It Solves

Prevents:

Abuse or denial-of-service attacks

Backend overload

Unfair resource usage

🔹 Key Idea

Allow up to N requests per user/IP per time window (e.g., 100 requests/minute).
Block or queue requests beyond that.

🔹 When to Use

For:

Public APIs

Authentication endpoints

Billing or subscription systems (where usage limits apply)

🔹 Example Scenario

User allowed 3 API calls per 5 seconds.

4th request gets HTTP 429 (“Too Many Requests”).

🔹 Common Tools

Bucket4j, Resilience4j RateLimiter

NGINX / Kong / AWS API Gateway

Redis + Sliding Window algorithm

⚡ 6️⃣ Combined Pattern (Retry + Circuit Breaker)
🔹 Definition

Combines both Retry and Circuit Breaker patterns to handle both temporary and persistent failures gracefully.

🔹 Problem It Solves

Sometimes failures are intermittent at first, then become persistent (e.g., service fully down).
You need retries for transient issues, but must stop trying after continuous failures.

🔹 Key Idea

Retry handles short-lived failures (retry few times).

Circuit Breaker stops requests after threshold failures to avoid wasting resources.

🔹 When to Use

When remote APIs are sometimes flaky, sometimes down.

In critical transactional workflows (payments, shipping, authentication).

🔹 Example Scenario

OrderService calls PaymentService.

Retry 3 times if failures occur.

If still failing repeatedly → CircuitBreaker opens → use fallback response.

🔹 Common Tools

Spring Retry + Resilience4j CircuitBreaker

Polly (.NET) supports both patterns together.*/

