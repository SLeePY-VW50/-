package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.lab.auth.AuthService;
import com.campus.lab.auth.dto.UserView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UserQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private SqlQueryCollector sqlQueryCollector;

    @Test
    void userListUsesTwoFixedQueriesInsteadOfRoleQueriesPerUser() {
        String account = createTestAccount("STUDENT");
        sqlQueryCollector.clear();

        List<UserView> users = authService.listUsers();

        assertThat(sqlQueryCollector.statements()).hasSize(2);
        assertThat(users).anyMatch(user -> user.account().equals(account)
                && user.roles().contains("STUDENT"));
        assertThat(users).anyMatch(user -> user.account().equals("A1001")
                && user.roles().contains("SYS_ADMIN"));
    }
}
