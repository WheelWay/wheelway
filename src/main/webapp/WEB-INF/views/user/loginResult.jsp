<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="kopo.poly.util.CmmUtil" %>
<%
    String ssUserName = CmmUtil.nvl((String) session.getAttribute("SS_USER_NAME"));
    String ssUserId = CmmUtil.nvl((String) session.getAttribute("SS_USER_ID"));
%>
<!doctype html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>로그인 성공</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/auth.css" rel="stylesheet">
</head>
<body class="auth-body">
<main class="auth-page"><section class="auth-card result-card" aria-labelledby="page-title">
    <h1 id="page-title">로그인 성공</h1>
    <p class="auth-message"><strong><%= ssUserName %></strong> 님이 로그인하였습니다.<br><%= ssUserId %></p>
    <a class="auth-button" href="/">메인 화면 이동</a>
</section></main>
</body>
</html>
