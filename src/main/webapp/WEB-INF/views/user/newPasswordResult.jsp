<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>비밀번호 재설정 결과</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/auth.css" rel="stylesheet">
</head>
<body class="auth-body">
<header class="auth-header"><a class="auth-wordmark" href="/">WheelWay</a><a class="auth-home-link" href="/">메인으로</a></header>
<main class="auth-page"><section class="auth-card result-card" aria-labelledby="page-title">
    <h1 id="page-title">비밀번호 재설정 결과</h1>
    <p class="auth-message"><c:out value="${msg}"/></p>
    <a class="auth-button" href="/user/login">로그인</a>
</section></main>
</body>
</html>
