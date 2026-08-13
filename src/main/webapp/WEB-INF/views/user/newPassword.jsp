<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>새 비밀번호 설정</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/auth.css" rel="stylesheet">
</head>
<body class="auth-body">
<main class="auth-page">
    <section class="auth-card" aria-labelledby="page-title">
        <c:choose>
            <c:when test="${not empty sessionScope.NEW_PASSWORD and not empty user.username}">
                <h1 id="page-title">새 비밀번호 설정</h1>
                <form id="f" method="post" action="/user/newPasswordProc" novalidate>
                    <div class="auth-field">
                        <label class="visually-hidden" for="password">새 비밀번호</label>
                        <input class="auth-input" type="password" name="password" id="password" placeholder="새 비밀번호 입력" autocomplete="new-password">
                    </div>
                    <div class="auth-field">
                        <label class="visually-hidden" for="password2">새 비밀번호 확인</label>
                        <input class="auth-input" type="password" name="password2" id="password2" placeholder="새 비밀번호 확인" autocomplete="new-password">
                    </div>
                    <button id="btnNewPassword" class="auth-button" type="submit">비밀번호 변경</button>
                </form>
                <nav class="auth-link-row" aria-label="계정 메뉴"><a href="/user/login">로그인</a></nav>
            </c:when>
            <c:otherwise>
                <h1 id="page-title">새 비밀번호 설정</h1>
                <p class="auth-message">비정상적인 접근입니다.<br>비밀번호 찾기 화면에서 다시 진행하세요.</p>
                <a class="auth-button" href="/user/searchPassword">비밀번호 찾기</a>
            </c:otherwise>
        </c:choose>
    </section>
</main>
<script>
    const form = document.getElementById('f');
    if (form) {
        form.addEventListener('submit', event => {
            const password = document.getElementById('password'); const password2 = document.getElementById('password2');
            if (password.value === '') { event.preventDefault(); alert('새 비밀번호를 입력하세요.'); password.focus(); }
            else if (password2.value === '') { event.preventDefault(); alert('새 비밀번호 확인을 입력하세요.'); password2.focus(); }
            else if (password.value !== password2.value) { event.preventDefault(); alert('입력한 비밀번호가 일치하지 않습니다.'); password.focus(); }
        });
    }
</script>
</body>
</html>
