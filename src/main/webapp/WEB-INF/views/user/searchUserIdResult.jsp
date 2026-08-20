<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>아이디 찾기 결과</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/auth.css" rel="stylesheet">
</head>
<body class="auth-body">
<header class="auth-header"><a class="auth-wordmark" href="/">WheelWay</a><a class="auth-home-link" href="/">메인으로</a></header>
<main class="auth-page"><section class="auth-card result-card" aria-labelledby="page-title">
    <h1 id="page-title">아이디 찾기 결과</h1>
    <p class="auth-message"><c:choose>
        <c:when test="${not empty user.username}"><c:out value="${user.name}"/> 회원님의 아이디는<br><strong><c:out value="${user.username}"/></strong>입니다.</c:when>
        <c:otherwise>일치하는 회원정보가 없습니다.</c:otherwise>
    </c:choose></p>
    <a class="auth-button" href="/user/login">로그인</a>
</section></main>
</body>
</html>
