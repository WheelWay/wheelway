<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!doctype html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>로그인</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/auth.css" rel="stylesheet">
</head>
<body class="auth-body">
<main class="auth-page">
    <section class="auth-card login-card" aria-labelledby="login-title">
        <div class="login-form-area">
            <h1 id="login-title">로그인</h1>
            <form id="f" novalidate>
                <div class="auth-field">
                    <label class="visually-hidden" for="userId">아이디</label>
                    <input class="auth-input" type="text" name="userId" id="userId" placeholder="아이디 입력" autocomplete="username" maxlength="20">
                </div>
                <div class="auth-field">
                    <label class="visually-hidden" for="password">비밀번호</label>
                    <input class="auth-input" type="password" name="password" id="password" placeholder="비밀번호 입력" autocomplete="current-password">
                </div>
                <nav class="login-find-row" aria-label="계정 찾기 메뉴">
                    <a class="login-find-link" href="/user/searchUserId"><span aria-hidden="true"></span>아이디 찾기</a>
                    <a class="login-find-link" href="/user/searchPassword"><span aria-hidden="true"></span>비밀번호 찾기</a>
                </nav>
                <button id="btnLogin" class="auth-button" type="submit">로그인하기</button>
            </form>
        </div>
        <div class="login-brand" aria-label="WheelWay 로고">
            <svg class="wheelway-logo" viewBox="0 0 250 210" preserveAspectRatio="xMidYMid meet" role="img" aria-label="WheelWay">
                <!-- Wheelchair user -->
                <circle cx="76" cy="37" r="12" fill="#1d75e6"/>
                <path d="M70 57c4-7 15-9 23-4l13 9h23c6 0 10 4 10 10s-4 10-10 10h-29c-3 0-6-1-8-3l-7-5 10 34h26c5 0 9 3 10 8l9 28c2 6-1 12-7 14-6 2-12-1-14-7l-7-21H87c-6 0-10-4-12-9L60 76c-2-7 2-15 10-19z" fill="#1d75e6"/>
                <path d="M66 69c-17 7-28 24-28 44 0 26 21 47 47 47 14 0 27-6 36-17" fill="none" stroke="#1d75e6" stroke-width="10" stroke-linecap="round"/>
                <!-- Location pin -->
                <path d="M166 28c-17 0-30 13-30 30 0 21 30 51 30 51s30-30 30-51c0-17-13-30-30-30z" fill="#19afa6"/>
                <circle cx="166" cy="58" r="10" fill="#fff"/>
                <!-- Wordmark and caption -->
                <text x="24" y="187" fill="#163f79" font-family="Arial, sans-serif" font-size="42" font-weight="700" letter-spacing="-2">Wheel</text>
                <text x="139" y="187" fill="#19a885" font-family="Arial, sans-serif" font-size="42" font-weight="700" letter-spacing="-2">Way</text>
                <text x="10" y="207" fill="#1d75e6" font-family="Arial, sans-serif" font-size="12" font-weight="700">휠체어 사용자를 위한 최적의 길찾기</text>
            </svg>
        </div>
    </section>
</main>
<script>
    const form = document.getElementById('f');
    const userId = document.getElementById('userId');
    const password = document.getElementById('password');
    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (userId.value.trim() === '') { alert('아이디를 입력하세요.'); userId.focus(); return; }
        if (password.value === '') { alert('비밀번호를 입력하세요.'); password.focus(); return; }
        try {
            const response = await fetch('/user/loginProc', { method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'}, body: new URLSearchParams(new FormData(form)) });
            const data = await response.json();
            if (data.result === 1) location.href = '/';
            else { alert(data.msg); userId.focus(); }
        } catch (error) { alert('서버에 연결하지 못했습니다.'); }
    });
</script>
</body>
</html>
