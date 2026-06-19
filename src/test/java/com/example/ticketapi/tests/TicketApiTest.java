package com.example.ticketapi.tests;

import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.testng.Assert.*;

/**
 * Автотесты для API заявок.
 * Используется RestAssured + TestNG.
 */
public class TicketApiTest {

    private static final String BASE_URL = "https://api.staging.example.com";
    private static final String TICKETS_ENDPOINT = "/api/tickets";

    private String userToken;
    private String adminToken;

    @BeforeClass
    public void setup() {
        RestAssured.baseURI = BASE_URL;

        // В реальном проекте токены получаем через POST /api/auth/login
        userToken = "user_token_from_auth_service";
        adminToken = "admin_token_from_auth_service";

        RestAssured.filters(
                new RequestLoggingFilter(),
                new ResponseLoggingFilter()
        );
    }


    @Test(description = "TC-01: Создание заявки с минимальными обязательными полями")
    public void testCreateTicketWithMinimalFields() {
        String requestBody = """
                {
                    "name": "Иван",
                    "phone": "+79161234567"
                }
                """;

        Response response = given()
                .auth().oauth2(userToken)
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post(TICKETS_ENDPOINT)
                .then()
                .statusCode(201)
                .extract().response();

        String actualStatus = response.jsonPath().getString("data.status");
        assertEquals(actualStatus, "new",
                "Новая заявка должна создаваться только со статусом 'new'");

        assertEquals(response.jsonPath().getString("data.name"), "Иван");
        assertEquals(response.jsonPath().getString("data.phone"), "+79161234567");

        Integer ticketId = response.jsonPath().getInt("data.id");
        assertNotNull(ticketId, "Ответ должен содержать id заявки");
        assertTrue(ticketId > 0, "ID заявки должен быть положительным числом");

        assertNull(response.jsonPath().getString("data.email"));
        assertNull(response.jsonPath().getString("data.comment"));
        assertNull(response.jsonPath().get("data.age"));
    }

    @Test(description = "TC-02: Создание заявки со всеми полями")
    public void testCreateTicketWithAllFields() {
        String requestBody = """
                {
                    "name": "Анна Смирнова",
                    "phone": "+79001234567",
                    "email": "anna@test.ru",
                    "comment": "Срочная заявка",
                    "age": 25
                }
                """;

        given()
                .auth().oauth2(userToken)
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post(TICKETS_ENDPOINT)
                .then()
                .statusCode(201)
                .body("data.status", equalTo("new"))
                .body("data.name", equalTo("Анна Смирнова"))
                .body("data.phone", equalTo("+79001234567"))
                .body("data.email", equalTo("anna@test.ru"))
                .body("data.comment", equalTo("Срочная заявка"))
                .body("data.age", equalTo(25))
                .body("data.id", notNullValue());
    }

    @Test(description = "TC-03: name = 1 символ — ошибка валидации (нижняя граница - 1)")
    public void testCreateTicketNameTooShort() {
        String requestBody = """
                {
                    "name": "A",
                    "phone": "+79161234567"
                }
                """;

        Response response = given()
                .auth().oauth2(userToken)
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post(TICKETS_ENDPOINT)
                .then()
                .statusCode(422)
                .extract().response();

        String errorBody = response.getBody().asString().toLowerCase();

        assertTrue(
                errorBody.contains("имя") || errorBody.contains("name"),
                "Сообщение об ошибке должно упоминать поле 'name'. Получено: " + errorBody
        );

        assertTrue(
                errorBody.contains("2") || errorBody.contains("двух") || errorBody.contains("минимум"),
                "Сообщение должно содержать информацию о минимальной длине. Получено: " + errorBody
        );

        assertNotNull(
                response.jsonPath().get("errors") != null
                        ? response.jsonPath().get("errors")
                        : response.jsonPath().get("error"),
                "Ответ должен содержать объект 'errors' или поле 'error'"
        );
    }

    @Test(description = "TC-04: name = 50 символов — верхняя граница (позитивный)")
    public void testCreateTicketNameExactly50Chars() {
        String name50 = "A".repeat(50);

        String requestBody = String.format("""
                {
                    "name": "%s",
                    "phone": "+79161234567"
                }
                """, name50);

        given()
                .auth().oauth2(userToken)
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post(TICKETS_ENDPOINT)
                .then()
                .statusCode(201)
                .body("data.name", equalTo(name50))
                .body("data.name.length()", equalTo(50));
    }

    @Test(description = "TC-05: name = 51 символ — ошибка (верхняя граница + 1)")
    public void testCreateTicketNameTooLong() {
        String name51 = "A".repeat(51);

        String requestBody = String.format("""
                {
                    "name": "%s",
                    "phone": "+79161234567"
                }
                """, name51);

        Response response = given()
                .auth().oauth2(userToken)
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post(TICKETS_ENDPOINT)
                .then()
                .statusCode(422)
                .extract().response();

        String errorBody = response.getBody().asString().toLowerCase();
        assertTrue(
                errorBody.contains("50") || errorBody.contains("максимум"),
                "Сообщение должно содержать информацию о максимальной длине"
        );
    }


    @Test(description = "TC-11: Админ не может перевести done → new")
    public void testAdminCannotMoveDoneToNew() {
        // 1. Создаём заявку пользователем
        String createBody = """
                {
                    "name": "Тест статусов",
                    "phone": "+79999999999"
                }
                """;

        int ticketId = given()
                .auth().oauth2(userToken)
                .contentType(ContentType.JSON)
                .body(createBody)
                .when()
                .post(TICKETS_ENDPOINT)
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getInt("data.id");

        for (String status : new String[]{"in_progress", "done"}) {
            String statusBody = String.format("{\"status\": \"%s\"}", status);
            given()
                    .auth().oauth2(adminToken)
                    .contentType(ContentType.JSON)
                    .body(statusBody)
                    .when()
                    .patch(TICKETS_ENDPOINT + "/" + ticketId)
                    .then()
                    .statusCode(200)
                    .body("data.status", equalTo(status));
        }

        String newStatusBody = "{\"status\": \"new\"}";

        Response response = given()
                .auth().oauth2(adminToken)
                .contentType(ContentType.JSON)
                .body(newStatusBody)
                .when()
                .patch(TICKETS_ENDPOINT + "/" + ticketId)
                .then()
                .statusCode(422)
                .extract().response();

        String errorText = response.getBody().asString().toLowerCase();
        assertTrue(
                errorText.contains("нельзя") || errorText.contains("cannot")
                        || errorText.contains("forbidden") || errorText.contains("запрещён"),
                "Должно быть осмысленное сообщение о запрете перехода. Получено: " + errorText
        );

        assertTrue(
                errorText.contains("done") || errorText.contains("new"),
                "Сообщение должно упоминать запрещённые статусы. Получено: " + errorText
        );
    }


    @Test(description = "TC-10: Пользователь не может получить чужую заявку")
    public void testUserCannotAccessForeignTicket() {
        Response response = given()
                .auth().oauth2(userToken)
                .contentType(ContentType.JSON)
                .when()
                .get(TICKETS_ENDPOINT + "/99999")
                .then()
                .statusCode(anyOf(equalTo(404), equalTo(403)))
                .extract().response();

        // Проверка, что в ответе нет данных чужой заявки
        String body = response.getBody().asString();
        assertFalse(
                body.contains("\"data\"") && response.jsonPath().get("data.id") != null,
                "Ответ не должен содержать данные чужой заявки"
        );
    }

    @Test(description = "Пользователь не может изменить статус своей заявки")
    public void testUserCannotChangeStatus() {
        // Создаём заявку
        int ticketId = given()
                .auth().oauth2(userToken)
                .contentType(ContentType.JSON)
                .body("{\"name\": \"Тест\", \"phone\": \"+79161234567\"}")
                .when()
                .post(TICKETS_ENDPOINT)
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getInt("data.id");

        Response response = given()
                .auth().oauth2(userToken)
                .contentType(ContentType.JSON)
                .body("{\"status\": \"done\"}")
                .when()
                .patch(TICKETS_ENDPOINT + "/" + ticketId);

        int statusCode = response.getStatusCode();

        if (statusCode == 200) {
            String actualStatus = response.jsonPath().getString("data.status");
            assertEquals(actualStatus, "new",
                    "Статус не должен измениться при попытке смены пользователем");
        } else {
            assertTrue(
                    statusCode == 403 || statusCode == 422,
                    "Ожидался 403 или 422 при попытке смены статуса пользователем, получен: "
                            + statusCode
            );
        }
    }
}