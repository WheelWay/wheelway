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
    <link href="/css/main.css?v=20260824-2" rel="stylesheet">
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
                <a class="hero-primary-button" href="/map.html">휠체어 길찾기 시작</a>
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
        <!--
          ★ 셋 다 <a href> 다. 세 기능이 전부 열렸으므로 '준비 중' 장치는 걷어냈다.

          이 자리는 네 번 깨졌다 — 화면을 새로 그릴 때마다 <button> 으로 돌아왔고,
          그때마다 '준비 중입니다' 알림이 먼저 떠서 /map.html 로 넘어가지 못했다.
          나중에 기능을 더 붙이더라도 <button> + 클릭 가로채기로 만들지 말 것.
          한 화면(map.html) 안에서 탭만 갈리는 구조라 링크 하나면 끝난다.

            지도  /map.html            버스  /map.html?tab=bus
            택시  /map.html?tab=taxi
        -->
        <div id="service-area" class="transport-menu">
            <a class="transport-button" href="/map.html">
                <svg class="transport-icon" viewBox="0 0 64 64" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="3" stroke-linejoin="round">
                    <path d="M6 12l17-6 18 6 17-6v46l-17 6-18-6-17 6V12z"/>
                    <path d="M23 6v46M41 12v46"/>
                </svg>
                <span class="transport-label">지도</span>
                <span class="transport-description">휠체어 이동 경로를 확인해요</span>
            </a>
            <a class="transport-button" href="/map.html?tab=bus">
                <svg class="transport-icon" viewBox="0 0 64 64" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="3" stroke-linejoin="round">
                    <path d="M10 17c0-5 4-9 9-9h25c5 0 10 4 10 9v25H10V17z"/>
                    <path d="M10 31h44M18 9v22M46 9v22M10 42h44v8H10z"/>
                    <circle cx="20" cy="51" r="4" fill="currentColor"/><circle cx="45" cy="51" r="4" fill="currentColor"/>
                </svg>
                <span class="transport-label">버스</span>
                <span class="transport-description">저상버스 정보를 찾아봐요</span>
            </a>
            <a class="transport-button" href="/map.html?tab=taxi">
                <svg class="transport-icon" viewBox="0 0 64 64" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="3" stroke-linejoin="round">
                    <path d="M9 30l7-13h32l7 13v21H9V30z"/>
                    <path d="M21 17l5-8h12l5 8M9 36h46M18 43h10M36 43h10"/>
                    <path d="M28 9h8"/>
                    <circle cx="18" cy="52" r="4" fill="currentColor"/><circle cx="46" cy="52" r="4" fill="currentColor"/>
                </svg>
                <span class="transport-label">택시</span>
                <span class="transport-description">편안한 택시 이동을 준비해요</span>
            </a>
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
            <!--
              아이디 찾기 결과. 예전에는 폼이 그냥 submit 돼서 /user/searchUserIdResult
              한 장으로 넘어갔는데, 모달을 열어놓고 쓰던 흐름이 거기서 끊겼다.
              같은 창 안에서 패널만 바꾼다.
            -->
            <div id="findIdResultPanel" class="modal-panel">
                <h1>아이디 찾기 결과</h1>
                <p id="findIdResultMessage" class="modal-result-message"></p>
                <a class="modal-login-submit" href="#loginModal" data-modal-panel="loginPanel">로그인</a>
                <div class="modal-back-row"><a href="#loginModal" data-modal-panel="findIdPanel">다시 찾기</a></div>
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
            <!--
              비밀번호 찾기 2단계. 예전에는 1단계 제출이 /user/newPassword 한 장으로 넘어가고
              거기서 또 /user/newPasswordResult 로 넘어갔다. 둘 다 이 창 안으로 들여왔다.
            -->
            <div id="newPasswordPanel" class="modal-panel">
                <h1>새 비밀번호 설정</h1>
                <form id="modalNewPasswordForm" novalidate>
                    <div class="modal-login-field">
                        <label class="visually-hidden" for="modalNewPassword">새 비밀번호</label>
                        <input id="modalNewPassword" type="password" name="password" placeholder="새 비밀번호 입력" autocomplete="new-password">
                    </div>
                    <!-- 확인 칸에는 name 이 없다. 서버로 보낼 값이 아니라 오타를 잡는 자리다. -->
                    <div class="modal-login-field">
                        <label class="visually-hidden" for="modalNewPassword2">새 비밀번호 확인</label>
                        <input id="modalNewPassword2" type="password" placeholder="새 비밀번호 확인" autocomplete="new-password">
                    </div>
                    <button class="modal-login-submit" type="submit">비밀번호 변경</button>
                    <div class="modal-back-row"><a href="#loginModal" data-modal-panel="loginPanel">로그인으로 돌아가기</a></div>
                </form>
            </div>
            <div id="findPasswordResultPanel" class="modal-panel">
                <h1>비밀번호 재설정 결과</h1>
                <p id="findPasswordResultMessage" class="modal-result-message"></p>
                <a class="modal-login-submit" href="#loginModal" data-modal-panel="loginPanel">로그인</a>
                <!-- 실패했을 때만 보인다. 성공한 사람에게 '다시 찾기' 는 할 일이 없는 링크다. -->
                <div class="modal-back-row" id="findPasswordRetryRow" hidden><a href="#loginModal" data-modal-panel="findPasswordPanel">다시 찾기</a></div>
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
    /*
      '준비 중' 알림은 걷어냈다. 지도·버스·택시가 전부 열려서 잡을 것이 없다 —
      남겨두면 링크를 가로채는 코드만 남아 다음 사람이 또 여기에 걸린다.
      아래 안내 줄(#serviceNotice)은 그대로 둔다. 무엇을 고르라는 말이지 상태 표시가 아니다.
    */

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

    /*
      아이디 찾기. ★ 페이지를 넘어가지 않는다 — 결과도 이 창 안에서 보여준다.
      그래서 화면용 /user/searchUserIdProc 가 아니라 JSON 을 주는 쪽을 부른다.

      이름은 사용자가 등록한 값이라 innerHTML 로 붙이지 않는다. 텍스트 노드로 넣는다.
    */
    const modalFindIdForm = document.getElementById('modalFindIdForm');
    const findIdResultMessage = document.getElementById('findIdResultMessage');

    modalFindIdForm.addEventListener('submit', async event => {
        event.preventDefault();
        const name = document.getElementById('modalFindIdName');
        const email = document.getElementById('modalFindIdEmail');
        if (name.value.trim() === '' || email.value.trim() === '') {
            alert(name.value.trim() === '' ? '이름을 입력하세요.' : '이메일을 입력하세요.');
            (name.value.trim() === '' ? name : email).focus();
            return;
        }

        try {
            const response = await fetch('/user/searchUserIdAjax', {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
                body: new URLSearchParams(new FormData(modalFindIdForm))
            });
            const data = await response.json();

            findIdResultMessage.textContent = '';
            findIdResultMessage.classList.toggle('is-fail', !data.found);

            if (data.found) {
                const id = document.createElement('strong');
                id.textContent = data.username;
                findIdResultMessage.append(data.name + ' 회원님의 아이디는',
                    document.createElement('br'), id, '입니다.');

                // 찾은 아이디를 로그인 칸에 미리 넣어둔다. [로그인] 을 누르면 비밀번호만 치면 된다.
                modalUserId.value = data.username;
            } else {
                findIdResultMessage.textContent = '일치하는 회원정보가 없습니다.';
            }

            showModalPanel('findIdResultPanel');
        } catch (error) {
            // '없다' 와 '못 물어봤다' 는 다른 말이다. 결과 패널로 넘기지 않는다.
            alert('서버에 연결하지 못했습니다.');
        }
    });

    /*
      비밀번호 찾기. 아이디 찾기와 같이 ★ 페이지를 넘어가지 않는다.
      2단계다 - 본인 확인이 되면 새 비밀번호 칸이 같은 창에서 열리고, 저장하면 결과가 뜬다.

      바꿀 대상(아이디)은 서버 세션에 있다. 화면은 들고 있지 않는다.
    */
    const modalFindPasswordForm = document.getElementById('modalFindPasswordForm');
    const modalNewPasswordForm = document.getElementById('modalNewPasswordForm');
    const modalNewPassword = document.getElementById('modalNewPassword');
    const modalNewPassword2 = document.getElementById('modalNewPassword2');
    const findPasswordResultMessage = document.getElementById('findPasswordResultMessage');
    const findPasswordRetryRow = document.getElementById('findPasswordRetryRow');

    addModalPasswordToggle(modalNewPassword);
    addModalPasswordToggle(modalNewPassword2);

    function showPasswordResult(ok, message) {
        findPasswordResultMessage.textContent = message;
        findPasswordResultMessage.classList.toggle('is-fail', !ok);
        findPasswordRetryRow.hidden = ok;
        showModalPanel('findPasswordResultPanel');
    }

    /** 비밀번호를 화면에 남겨두지 않는다. 창을 닫아도 값이 남으면 다음 사람이 본다. */
    function clearNewPassword() {
        modalNewPassword.value = '';
        modalNewPassword2.value = '';
    }

    modalFindPasswordForm.addEventListener('submit', async event => {
        event.preventDefault();
        const fields = ['modalFindPasswordName', 'modalFindPasswordId', 'modalFindPasswordEmail']
            .map(id => document.getElementById(id));
        const emptyField = fields.find(field => field.value.trim() === '');
        if (emptyField) {
            alert(emptyField === fields[0] ? '이름을 입력하세요.' : emptyField === fields[1] ? '아이디를 입력하세요.' : '이메일을 입력하세요.');
            emptyField.focus();
            return;
        }

        try {
            const response = await fetch('/user/searchPasswordAjax', {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
                body: new URLSearchParams(new FormData(modalFindPasswordForm))
            });
            const data = await response.json();

            if (data.found) {
                clearNewPassword();
                showModalPanel('newPasswordPanel');
                modalNewPassword.focus();
            } else {
                showPasswordResult(false, '일치하는 회원정보가 없습니다.');
            }
        } catch (error) {
            // '없다' 와 '못 물어봤다' 는 다른 말이다. 결과 패널로 넘기지 않는다.
            alert('서버에 연결하지 못했습니다.');
        }
    });

    modalNewPasswordForm.addEventListener('submit', async event => {
        event.preventDefault();
        if (modalNewPassword.value === '') { alert('새 비밀번호를 입력하세요.'); modalNewPassword.focus(); return; }
        if (modalNewPassword2.value === '') { alert('새 비밀번호 확인을 입력하세요.'); modalNewPassword2.focus(); return; }
        if (modalNewPassword.value !== modalNewPassword2.value) { alert('입력한 비밀번호가 일치하지 않습니다.'); modalNewPassword.focus(); return; }

        try {
            const response = await fetch('/user/newPasswordAjax', {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
                body: new URLSearchParams({password: modalNewPassword.value})
            });
            const data = await response.json();
            // MsgDTO 는 @JsonInclude(NON_DEFAULT) 라 result 가 0 이면 필드 자체가 안 온다.
            showPasswordResult(data.result === 1, data.msg);
        } catch (error) {
            alert('서버에 연결하지 못했습니다.');
        } finally {
            clearNewPassword();
        }
    });
</script>
</body>
</html>
