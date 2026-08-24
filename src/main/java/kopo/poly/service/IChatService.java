package kopo.poly.service;

import java.util.List;

/**
 * 길 안내 도우미. <b>말 한 마디를 받아 경로 하나를 낸다.</b>
 *
 * <h3>어디까지가 LLM 이고 어디부터가 우리인가</h3>
 * <pre>
 *   사용자의 말   "청주시청 가는 길 알려줘"
 *        ↓  Gemini            목적지 이름만 뽑는다        ← 여기까지가 LLM
 *   목적지        "청주시청"
 *        ↓  정류장 캐시 / 카카오   좌표는 실제 데이터에서
 *   좌표          36.6424, 127.4890
 *        ↓  IRouteService      길찾기는 우리 엔진
 *   사실          771m · 계단 없음
 *        ↓  템플릿             숫자는 우리가 준 것만 쓴다
 *   답            "청주시청까지 약 12분입니다"
 * </pre>
 * <b>숫자를 만드는 자리에 LLM 이 없다.</b> 그래서 환각이 들어올 자리가 없다.
 *
 * <h3>출발지</h3>
 * 화면이 이미 정해둔 출발지가 있으면 그것, 없으면 등록해둔 집, 둘 다 없으면 되묻는다.
 * 챗봇이 "집에서 출발" 을 기본 전제로 삼기 때문에 {@code USER_PLACES} 가 먼저 필요했다.
 */
public interface IChatService {

    /**
     * 챗봇이 화면에 돌려주는 한 마디.
     *
     * @param answer      말풍선에 뜰 문장. <b>항상 채워진다</b> — 실패해도 왜 실패했는지 말한다
     * @param heard       무엇으로 알아들었는가. 음성일 때 '무엇을 잘못 들었는지' 를 보여주려고 둔다
     * @param destination 확정된 목적지 이름. 못 정했으면 {@code null}
     * @param matched     안내할 수 있는 목적지를 찾았는가
     * @param start       출발 좌표 {@code [위도, 경도]}. 경로가 없으면 {@code null}
     * @param end         도착 좌표 {@code [위도, 경도]}. 경로가 없으면 {@code null}
     * @param path        지도에 그릴 경로. 없으면 {@code null}
     * @param distanceM   거리(m). 경로가 없으면 0
     * @param minutes     예상 소요(분). 경로가 없으면 0
     * @param needStart   출발지를 정해달라고 되물어야 하는가.
     *                    화면이 '현재위치로 할까요' 를 띄울지 판단한다
     */
    record Answer(String answer,
                  String heard,
                  String destination,
                  boolean matched,
                  double[] start,
                  double[] end,
                  List<double[]> path,
                  double distanceM,
                  int minutes,
                  boolean needStart) {
    }

    /**
     * 한 마디를 처리한다.
     *
     * <p><b>예외를 던지지 않는다.</b> 어떤 단계가 실패하든 사람이 읽을 문장으로 답한다 —
     * 챗봇이 500 을 내면 화면은 할 말이 없어진다.
     *
     * @param username  로그인 아이디. 집 주소를 찾는 데 쓴다. {@code null} 이면 비로그인
     * @param utterance 사용자가 한 말(또는 친 글)
     * @param hereLat   화면이 <b>이미 정해둔 출발지</b>. 없으면 {@code null}.
     *                  검색해 찍었거나 현재위치를 잡았거나 집 버튼으로 넣은 값이다
     * @param hereLng   같음
     */
    Answer ask(String username, String utterance, Double hereLat, Double hereLng);

    /**
     * 말소리 한 마디를 처리한다. 전사한 뒤 {@link #ask} 로 그대로 넘어간다.
     *
     * <p><b>여기서 새로 하는 일은 전사뿐이다.</b> 목적지 해석·좌표·길찾기·답 문장은
     * 글로 물었을 때와 완전히 같은 길을 탄다 — 그래서 글 쪽이 먼저 검증되면
     * 음성은 앞단만 갈아끼우는 일이 된다.
     *
     * <p>{@code Answer.heard} 에 전사문이 담긴다. 화면이 그것을 흐리게 보여줘야
     * 사용자가 <b>무엇을 잘못 들었는지</b> 알고 다시 말할지 고쳐 칠지 정할 수 있다.
     *
     * @param wav 16kHz · mono · 16bit PCM WAV
     */
    Answer askVoice(String username, byte[] wav, Double hereLat, Double hereLng);
}
