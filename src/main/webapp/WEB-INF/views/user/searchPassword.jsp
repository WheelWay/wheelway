<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!doctype html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>비밀번호 재설정</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/auth.css" rel="stylesheet">
</head>
<body class="auth-body">
<main class="auth-page">
    <section class="auth-card" aria-labelledby="page-title">
        <h1 id="page-title">비밀번호 재설정</h1>
        <form id="f" method="post" action="/user/searchPasswordProc" novalidate>
            <div class="auth-field">
                <label class="auth-label" for="userName">이름</label>
                <input class="auth-input" type="text" name="userName" id="userName" placeholder="이름 입력" maxlength="50" autocomplete="name">
            </div>
            <div class="auth-field">
                <label class="auth-label" for="userId">아이디</label>
                <input class="auth-input" type="text" name="userId" id="userId" placeholder="아이디 입력" maxlength="20" autocomplete="username">
            </div>
            <div class="auth-field">
                <label class="auth-label" for="email">이메일</label>
                <input class="auth-input" type="email" name="email" id="email" placeholder="이메일 입력" maxlength="255" autocomplete="email">
            </div>
            <button id="btnSearchPassword" class="auth-button" type="submit">비밀번호 찾기</button>
        </form>
        <nav class="auth-link-row" aria-label="계정 메뉴"><a href="/user/login">로그인</a><a href="/user/searchUserId">아이디 찾기</a></nav>
    </section>
</main>
<script>
    const form = document.getElementById('f');
    form.addEventListener('submit', event => {
        for (const id of ['userName', 'userId', 'email']) {
            const field = document.getElementById(id);
            if (field.value.trim() === '') {
                event.preventDefault();
                alert((id === 'userName' ? '이름' : id === 'userId' ? '아이디' : '이메일') + '을 입력하세요.');
                field.focus(); return;
            }
        }
    });
</script>
</body>
</html>
