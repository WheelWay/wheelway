<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%
    boolean isLoggedIn = session.getAttribute("SS_USER_ID") != null;
%>
<!doctype html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>WheelWay</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/main.css?v=20260819-27" rel="stylesheet">
</head>
<body class="main-body">
<main class="main-shell">
    <header class="main-header">
        <a class="main-wordmark" href="/">WheelWay</a>
        <nav class="header-actions<%= isLoggedIn ? " is-member" : "" %>" aria-label="사용자 메뉴">
            <% if (isLoggedIn) { %>
                <a class="header-button" href="/user/logout">로그아웃</a>
                <button class="header-button my-page-button" type="button">마이페이지</button>
            <% } else { %>
                <a id="openLoginModal" class="header-button" href="#loginModal">로그인</a>
                <a class="header-button" href="/user/userRegForm">회원가입</a>
            <% } %>
        </nav>
    </header>

    <section class="main-content" aria-label="WheelWay 서비스 선택">
        <div class="main-hero">
            <p class="hero-kicker">WHEELCHAIR MOBILITY SERVICE</p>
            <h1>휠체어 이동을 위한<br>더 편안한 길찾기</h1>
            <p class="hero-description">내게 맞는 이동 수단을 선택하고, 편안한 이동을 시작해 보세요.</p>
            <div class="hero-actions">
                <button class="hero-primary-button" type="button" data-service="지도">휠체어 길찾기 시작</button>
                <a class="hero-secondary-link" href="#service-area">이동 서비스 둘러보기</a>
            </div>
        </div>

        <svg class="main-logo" viewBox="0 0 250 235" preserveAspectRatio="xMidYMid meet" role="img" aria-label="WheelWay">
            <circle cx="76" cy="37" r="12" fill="#1d75e6"/>
            <path d="M70 57c4-7 15-9 23-4l13 9h23c6 0 10 4 10 10s-4 10-10 10h-29c-3 0-6-1-8-3l-7-5 10 34h26c5 0 9 3 10 8l9 28c2 6-1 12-7 14-6 2-12-1-14-7l-7-21H87c-6 0-10-4-12-9L60 76c-2-7 2-15 10-19z" fill="#1d75e6"/>
            <path d="M66 69c-17 7-28 24-28 44 0 26 21 47 47 47 14 0 27-6 36-17" fill="none" stroke="#1d75e6" stroke-width="10" stroke-linecap="round"/>
            <g transform="translate(30 11) scale(.82)">
                <path d="M166 28c-17 0-30 13-30 30 0 21 30 51 30 51s30-30 30-51c0-17-13-30-30-30z" fill="#19afa6"/>
                <circle cx="166" cy="58" r="10" fill="#fff"/>
            </g>
            <text x="24" y="205" fill="#163f79" font-family="Arial, sans-serif" font-size="42" font-weight="700" letter-spacing="-2">Wheel</text>
            <text x="139" y="205" fill="#19a885" font-family="Arial, sans-serif" font-size="42" font-weight="700" letter-spacing="-2">Way</text>
            <text x="125" y="228" text-anchor="middle" font-family="Arial, sans-serif" font-size="12" font-weight="700">
                <tspan fill="#1d75e6">휠체어 사용자</tspan><tspan fill="#111">를 위한 최적의 </tspan><tspan fill="#19a885">길찾기</tspan>
            </text>
        </svg>

        <aside class="journey-card" aria-label="WheelWay 서비스 안내">
            <span class="journey-card-label">WHEELWAY GUIDE</span>
            <strong>오늘의 이동을<br>더 편안하게 준비하세요.</strong>
            <p>휠체어 이동에 맞는 길찾기와 교통수단 정보를 한곳에서 확인할 수 있어요.</p>
            <span class="journey-card-chip">휠체어 친화 이동</span>
        </aside>
        <div id="service-area" class="transport-menu">
            <button class="transport-button" type="button" data-service="지도">
                <svg class="transport-icon" viewBox="0 0 64 64" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="3" stroke-linejoin="round">
                    <path d="M6 12l17-6 18 6 17-6v46l-17 6-18-6-17 6V12z"/>
                    <path d="M23 6v46M41 12v46"/>
                </svg>
                <span class="transport-label">지도</span>
                <span class="transport-description">휠체어 이동 경로를 확인해요</span>
            </button>
            <button class="transport-button" type="button" data-service="버스">
                <svg class="transport-icon" viewBox="0 0 64 64" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="3" stroke-linejoin="round">
                    <path d="M10 17c0-5 4-9 9-9h25c5 0 10 4 10 9v25H10V17z"/>
                    <path d="M10 31h44M18 9v22M46 9v22M10 42h44v8H10z"/>
                    <circle cx="20" cy="51" r="4" fill="currentColor"/><circle cx="45" cy="51" r="4" fill="currentColor"/>
                </svg>
                <span class="transport-label">버스</span>
                <span class="transport-description">저상버스 정보를 찾아봐요</span>
            </button>
            <button class="transport-button" type="button" data-service="택시">
                <svg class="transport-icon" viewBox="0 0 64 64" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="3" stroke-linejoin="round">
                    <path d="M9 30l7-13h32l7 13v21H9V30z"/>
                    <path d="M21 17l5-8h12l5 8M9 36h46M18 43h10M36 43h10"/>
                    <path d="M28 9h8"/>
                    <circle cx="18" cy="52" r="4" fill="currentColor"/><circle cx="46" cy="52" r="4" fill="currentColor"/>
                </svg>
                <span class="transport-label">택시</span>
                <span class="transport-description">편안한 택시 이동을 준비해요</span>
            </button>
        </div>
        <p id="serviceNotice" class="service-notice is-visible" role="status" aria-live="polite">서비스 선택 · 원하는 이동 서비스를 선택해 주세요.</p>
    </section>
</main>
<div id="loginModal" class="login-modal" role="dialog" aria-modal="true" aria-labelledby="modal-login-title" aria-hidden="true">
    <div class="login-modal-card">
        <section class="login-modal-form-area">
            <a id="closeLoginModal" class="login-modal-close" href="#" aria-label="로그인 창 닫기">×</a>
            <div id="loginPanel" class="modal-panel is-active">
                <h1 id="modal-login-title">로그인</h1>
                <p class="modal-login-intro">WheelWay와 함께 더 편안한 이동을 시작하세요.</p>
                <form id="modalLoginForm" novalidate>
                    <div class="modal-login-field">
                        <label class="visually-hidden" for="modalUserId">아이디</label>
                        <input id="modalUserId" type="text" name="userId" placeholder="아이디 입력" autocomplete="username" maxlength="20">
                    </div>
                    <div class="modal-login-field">
                        <label class="visually-hidden" for="modalPassword">비밀번호</label>
                        <input id="modalPassword" type="password" name="password" placeholder="비밀번호 입력" autocomplete="current-password">
                    </div>
                    <label class="modal-remember"><input id="modalRememberUserId" type="checkbox"> <span>아이디 저장</span></label>
                    <div class="modal-find-row">
                        <a href="#loginModal" data-modal-panel="findIdPanel">아이디 찾기</a>
                        <a href="#loginModal" data-modal-panel="findPasswordPanel">비밀번호 찾기</a>
                    </div>
                    <button class="modal-login-submit" type="submit">로그인하기</button>
                </form>
            </div>
            <div id="findIdPanel" class="modal-panel">
                <h1>아이디 찾기</h1>
                <form id="modalFindIdForm" method="post" action="/user/searchUserIdProc" novalidate>
                    <div class="modal-login-field">
                        <label class="visually-hidden" for="modalFindIdName">이름</label>
                        <input id="modalFindIdName" type="text" name="userName" placeholder="이름 입력" maxlength="50" autocomplete="name">
                    </div>
                    <div class="modal-login-field">
                        <label class="visually-hidden" for="modalFindIdEmail">이메일</label>
                        <input id="modalFindIdEmail" type="email" name="email" placeholder="이메일 입력" maxlength="255" autocomplete="email">
                    </div>
                    <button class="modal-login-submit" type="submit">아이디 확인하기</button>
                    <div class="modal-back-row"><a href="#loginModal" data-modal-panel="loginPanel">로그인으로 돌아가기</a></div>
                </form>
            </div>
            <div id="findPasswordPanel" class="modal-panel">
                <h1>비밀번호 재설정</h1>
                <form id="modalFindPasswordForm" method="post" action="/user/searchPasswordProc" novalidate>
                    <div class="modal-login-field">
                        <label class="visually-hidden" for="modalFindPasswordName">이름</label>
                        <input id="modalFindPasswordName" type="text" name="userName" placeholder="이름 입력" maxlength="50" autocomplete="name">
                    </div>
                    <div class="modal-login-field">
                        <label class="visually-hidden" for="modalFindPasswordId">아이디</label>
                        <input id="modalFindPasswordId" type="text" name="userId" placeholder="아이디 입력" maxlength="20" autocomplete="username">
                    </div>
                    <div class="modal-login-field">
                        <label class="visually-hidden" for="modalFindPasswordEmail">이메일</label>
                        <input id="modalFindPasswordEmail" type="email" name="email" placeholder="이메일 입력" maxlength="255" autocomplete="email">
                    </div>
                    <button class="modal-login-submit" type="submit">비밀번호 찾기</button>
                    <div class="modal-back-row"><a href="#loginModal" data-modal-panel="loginPanel">로그인으로 돌아가기</a></div>
                </form>
            </div>
        </section>
        <section class="login-modal-brand" aria-label="WheelWay 로고">
            <svg viewBox="0 0 250 235" preserveAspectRatio="xMidYMid meet" role="img" aria-label="WheelWay">
                <circle cx="76" cy="37" r="12" fill="#1d75e6"/>
                <path d="M70 57c4-7 15-9 23-4l13 9h23c6 0 10 4 10 10s-4 10-10 10h-29c-3 0-6-1-8-3l-7-5 10 34h26c5 0 9 3 10 8l9 28c2 6-1 12-7 14-6 2-12-1-14-7l-7-21H87c-6 0-10-4-12-9L60 76c-2-7 2-15 10-19z" fill="#1d75e6"/>
                <path d="M66 69c-17 7-28 24-28 44 0 26 21 47 47 47 14 0 27-6 36-17" fill="none" stroke="#1d75e6" stroke-width="10" stroke-linecap="round"/>
                <g transform="translate(30 11) scale(.82)"><path d="M166 28c-17 0-30 13-30 30 0 21 30 51 30 51s30-30 30-51c0-17-13-30-30-30z" fill="#19afa6"/><circle cx="166" cy="58" r="10" fill="#fff"/></g>
                <text x="24" y="205" fill="#163f79" font-family="Arial, sans-serif" font-size="42" font-weight="700" letter-spacing="-2">Wheel</text>
                <text x="139" y="205" fill="#19a885" font-family="Arial, sans-serif" font-size="42" font-weight="700" letter-spacing="-2">Way</text>
                <text x="125" y="228" text-anchor="middle" font-family="Arial, sans-serif" font-size="12" font-weight="700"><tspan fill="#1d75e6">휠체어 사용자</tspan><tspan fill="#111">를 위한 최적의 </tspan><tspan fill="#19a885">길찾기</tspan></text>
            </svg>
        </section>
    </div>
</div>
<script>
    const serviceNotice = document.getElementById('serviceNotice');
    document.querySelectorAll('[data-service]').forEach(button => {
        button.addEventListener('click', () => {
            serviceNotice.textContent = button.dataset.service + ' 서비스는 현재 준비 중입니다.';
            serviceNotice.classList.add('is-visible');
        });
    });

    const loginModal = document.getElementById('loginModal');
    const loginModalCard = document.querySelector('.login-modal-card');
    const modalLoginForm = document.getElementById('modalLoginForm');
    const modalUserId = document.getElementById('modalUserId');
    const modalPassword = document.getElementById('modalPassword');
    function addModalPasswordToggle(input) {
        const wrapper = document.createElement('div');
        wrapper.className = 'modal-password-wrap';
        input.parentNode.insertBefore(wrapper, input);
        wrapper.appendChild(input);

        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'modal-password-toggle';
        button.setAttribute('aria-label', '비밀번호 보기');
        button.setAttribute('aria-pressed', 'false');
        button.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2.5 12s3.5-5 9.5-5 9.5 5 9.5 5-3.5 5-9.5 5-9.5-5-9.5-5Z"/><circle cx="12" cy="12" r="2.6"/></svg>';
        button.addEventListener('click', () => {
            const visible = input.type === 'password';
            input.type = visible ? 'text' : 'password';
            button.setAttribute('aria-label', visible ? '비밀번호 숨기기' : '비밀번호 보기');
            button.setAttribute('aria-pressed', String(visible));
        });
        wrapper.appendChild(button);
    }
    addModalPasswordToggle(modalPassword);
    const modalRememberUserId = document.getElementById('modalRememberUserId');
    const savedModalUserId = localStorage.getItem('wheelway.savedUserId');
    if (savedModalUserId) { modalUserId.value = savedModalUserId; modalRememberUserId.checked = true; }
    const modalPanels = document.querySelectorAll('.modal-panel');

    function showModalPanel(panelId) {
        modalPanels.forEach(panel => panel.classList.toggle('is-active', panel.id === panelId));
        loginModalCard.classList.toggle('is-compact', panelId !== 'loginPanel');
        loginModalCard.classList.toggle('is-password-search', panelId === 'findPasswordPanel');
    }

    function closeLoginModal() {
        loginModal.classList.remove('is-open');
        loginModal.setAttribute('aria-hidden', 'true');
        showModalPanel('loginPanel');
    }

    const openLoginModal = document.getElementById('openLoginModal');
    if (openLoginModal) {
        openLoginModal.addEventListener('click', event => {
            event.preventDefault();
            loginModal.classList.add('is-open');
            loginModal.setAttribute('aria-hidden', 'false');
            showModalPanel('loginPanel');
            modalUserId.focus();
        });
    }

    document.querySelectorAll('[data-modal-panel]').forEach(link => {
        link.addEventListener('click', event => {
            event.preventDefault();
            loginModal.classList.add('is-open');
            loginModal.setAttribute('aria-hidden', 'false');
            showModalPanel(link.dataset.modalPanel);
            document.querySelector('#' + link.dataset.modalPanel + ' input')?.focus();
        });
    });

    document.getElementById('closeLoginModal').addEventListener('click', closeLoginModal);
    loginModal.addEventListener('click', event => { if (event.target === loginModal) closeLoginModal(); });
    document.addEventListener('keydown', event => { if (event.key === 'Escape') closeLoginModal(); });

    modalLoginForm.addEventListener('submit', async event => {
        event.preventDefault();
        if (modalUserId.value.trim() === '') { alert('아이디를 입력하세요.'); modalUserId.focus(); return; }
        if (modalPassword.value === '') { alert('비밀번호를 입력하세요.'); modalPassword.focus(); return; }
        try {
            const response = await fetch('/user/loginProc', { method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'}, body: new URLSearchParams(new FormData(modalLoginForm)) });
            const data = await response.json();
            if (data.result === 1) {
                if (modalRememberUserId.checked) localStorage.setItem('wheelway.savedUserId', modalUserId.value.trim());
                else localStorage.removeItem('wheelway.savedUserId');
                location.href = '/';
            }
            else { alert(data.msg); modalUserId.focus(); }
        } catch (error) { alert('서버에 연결하지 못했습니다.'); }
    });

    document.getElementById('modalFindIdForm').addEventListener('submit', event => {
        const name = document.getElementById('modalFindIdName');
        const email = document.getElementById('modalFindIdEmail');
        if (name.value.trim() === '' || email.value.trim() === '') {
            event.preventDefault();
            alert(name.value.trim() === '' ? '이름을 입력하세요.' : '이메일을 입력하세요.');
            (name.value.trim() === '' ? name : email).focus();
        }
    });

    document.getElementById('modalFindPasswordForm').addEventListener('submit', event => {
        const fields = ['modalFindPasswordName', 'modalFindPasswordId', 'modalFindPasswordEmail']
            .map(id => document.getElementById(id));
        const emptyField = fields.find(field => field.value.trim() === '');
        if (emptyField) {
            event.preventDefault();
            alert(emptyField === fields[0] ? '이름을 입력하세요.' : emptyField === fields[1] ? '아이디를 입력하세요.' : '이메일을 입력하세요.');
            emptyField.focus();
        }
    });
</script>
</body>
</html>
