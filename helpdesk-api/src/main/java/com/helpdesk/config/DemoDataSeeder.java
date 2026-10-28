package com.helpdesk.config;

import com.helpdesk.domain.*;
import com.helpdesk.domain.Enums.AuthorType;
import com.helpdesk.domain.Enums.CustomerTier;
import com.helpdesk.domain.Enums.OrderStatus;
import com.helpdesk.domain.Enums.TicketCategory;
import com.helpdesk.repository.CustomerRepository;
import com.helpdesk.repository.OrderRepository;
import com.helpdesk.repository.SupportAgentRepository;
import com.helpdesk.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Seeds a believable starting state: a few customers with order history, three
 * support agents, and tickets in different stages, including some that are
 * untriaged (for the AI triage agent later) and one that has breached its SLA.
 *
 * Turn off with helpdesk.seed-demo-data=false.
 */
@Component
@ConditionalOnProperty(name = "helpdesk.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final SupportAgentRepository agents;
    private final TicketRepository tickets;
    private final Clock clock;

    public DemoDataSeeder(CustomerRepository customers, OrderRepository orders, SupportAgentRepository agents,
                          TicketRepository tickets, Clock clock) {
        this.customers = customers;
        this.orders = orders;
        this.agents = agents;
        this.tickets = tickets;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (customers.count() > 0) {
            return;
        }
        Instant now = clock.instant();

        // --- support agents -------------------------------------------------
        SupportAgent maya = agents.save(new SupportAgent("Maya Chen", "maya.chen@helpdesk.example", "Billing"));
        agents.save(new SupportAgent("Luis Ortega", "luis.ortega@helpdesk.example", "Logistics"));
        SupportAgent priya = agents.save(new SupportAgent("Priya Nair", "priya.nair@helpdesk.example", "Technical"));

        // --- customers ------------------------------------------------------
        Customer alice = customers.save(new Customer("Alice Johnson", "alice@example.com", CustomerTier.PREMIUM, ago(now, 400)));
        Customer bob = customers.save(new Customer("Bob Smith", "bob@example.com", CustomerTier.STANDARD, ago(now, 200)));
        Customer carla = customers.save(new Customer("Carla Diaz", "carla@example.com", CustomerTier.STANDARD, ago(now, 90)));
        Customer dev = customers.save(new Customer("Dev Patel", "dev@example.com", CustomerTier.PREMIUM, ago(now, 30)));

        // --- orders ---------------------------------------------------------
        CustomerOrder aliceHeadphones = order("ORD-10001", alice, "Noise-cancelling headphones", "249.99", OrderStatus.DELIVERED, "1Z999AA10000001", ago(now, 20));
        CustomerOrder aliceDock = order("ORD-10002", alice, "USB-C docking station", "129.00", OrderStatus.SHIPPED, "1Z999AA10000002", ago(now, 9));
        CustomerOrder bobKeyboard = order("ORD-10003", bob, "Mechanical keyboard", "89.50", OrderStatus.DELIVERED, "1Z999AA10000003", ago(now, 6));
        order("ORD-10004", bob, "27-inch 4K monitor", "319.00", OrderStatus.PLACED, null, ago(now, 1));
        CustomerOrder carlaWatch = order("ORD-10005", carla, "Smart watch", "199.00", OrderStatus.CANCELLED, null, ago(now, 12));
        order("ORD-10006", carla, "Phone case", "19.99", OrderStatus.DELIVERED, "1Z999AA10000006", ago(now, 40));
        order("ORD-10007", dev, "14-inch laptop", "1499.00", OrderStatus.SHIPPED, "1Z999AA10000007", ago(now, 3));
        order("ORD-10008", dev, "Laptop sleeve", "39.00", OrderStatus.DELIVERED, "1Z999AA10000008", ago(now, 15));

        // --- tickets --------------------------------------------------------
        // 1. Fresh and untriaged: a good first candidate for AI triage.
        tickets.save(new Ticket(bob, bobKeyboard, "Keyboard arrived with broken keys",
                "Hi, my keyboard arrived yesterday and the E and R keys don't register at all. "
                        + "I'd like a replacement or a refund please.",
                TicketPriority.MEDIUM, now.minus(Duration.ofHours(2))));

        // 2. Premium customer, untriaged, SLA already breached (premium HIGH = 4h).
        tickets.save(new Ticket(alice, aliceDock, "Where is my docking station?",
                "Tracking hasn't updated in five days and I need the dock for work on Monday. "
                        + "This is really frustrating.",
                TicketPriority.HIGH, now.minus(Duration.ofHours(10))));

        // 3. Being worked on, waiting for the customer to reply.
        Instant t3 = now.minus(Duration.ofDays(1));
        Ticket billing = new Ticket(carla, carlaWatch, "Charged for a cancelled order",
                "I cancelled my smart watch order but my card was still charged $199.", TicketPriority.HIGH, t3);
        billing.triage(TicketCategory.BILLING, null, t3.plus(Duration.ofMinutes(15)));
        billing.assignTo(maya, t3.plus(Duration.ofMinutes(20)));
        billing.transitionTo(TicketStatus.WAITING_ON_CUSTOMER,
                "Sorry about that, Carla. Could you confirm the last 4 digits of the card that was charged?",
                t3.plus(Duration.ofMinutes(45)));
        tickets.save(billing);

        // 4. Fully resolved and closed: shows the end of the lifecycle.
        Instant t4 = now.minus(Duration.ofDays(3));
        Ticket account = new Ticket(dev, null, "Can't reset my password",
                "The password reset email never arrives.", TicketPriority.LOW, t4);
        account.triage(TicketCategory.ACCOUNT, null, t4.plus(Duration.ofMinutes(5)));
        account.assignTo(priya, t4.plus(Duration.ofMinutes(10)));
        account.addComment(AuthorType.AGENT, priya.getName(),
                "Your email provider was blocking our domain. I've sent the reset link from our backup sender.",
                false, t4.plus(Duration.ofHours(1)));
        account.addComment(AuthorType.CUSTOMER, dev.getName(), "Got it, all sorted. Thanks!", false,
                t4.plus(Duration.ofHours(2)));
        account.transitionTo(TicketStatus.RESOLVED, null, t4.plus(Duration.ofHours(2).plusMinutes(5)));
        account.transitionTo(TicketStatus.CLOSED, null, t4.plus(Duration.ofDays(1)));
        tickets.save(account);

        // 5. Very fresh, low priority, untriaged.
        tickets.save(new Ticket(alice, aliceHeadphones, "Left earcup crackles",
                "The left side of my headphones crackles at higher volumes. Is this covered by warranty?",
                TicketPriority.LOW, now.minus(Duration.ofMinutes(30))));

        log.info("Seeded demo data: {} customers, {} orders, {} agents, {} tickets",
                customers.count(), orders.count(), agents.count(), tickets.count());
    }

    private CustomerOrder order(String number, Customer customer, String product, String amount,
                                OrderStatus status, String tracking, Instant orderedAt) {
        return orders.save(new CustomerOrder(number, customer, product, new BigDecimal(amount), status, tracking, orderedAt));
    }

    private static Instant ago(Instant now, int days) {
        return now.minus(Duration.ofDays(days));
    }
}
