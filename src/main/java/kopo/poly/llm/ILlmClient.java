package kopo.poly.llm;

import java.util.List;

/**
 * 사람이 한 말에서 <b>목적지 이름 하나</b>를 뽑는다.
 *
 * <h3>★ 이것이 LLM 이 하는 일의 전부다. 길은 찾지 않는다.</h3>
 * 처음 구상은 {@code Whisper → Gemini 가 길을 찾아 알려줌} 이었는데 그건 쓸 수 없다.
 * <ul>
 *   <li>LLM 은 좌표와 거리를 <b>지어낸다.</b> "약 700m, 도보 11분" 이 그럴듯하게 나오는데
 *       근거가 없고, 자기가 틀렸다는 것을 모른다</li>
 *   <li>계단·지하·공사·제보 하드필터가 전부 <b>우리 그래프 안</b>에 있다. LLM 은 그걸 모르므로
 *       계단이 있는 길을 안내하게 된다. 그 순간 이 서비스는 그냥 도보 안내가 된다</li>
 *   <li>틀렸을 때 대가가 크다. 휠체어 사용자가 계단 앞에 서면 되돌아가야 한다</li>
 * </ul>
 * <b>LLM 은 통역사여야지 길잡이면 안 된다.</b> 거리·시간·경사는 전부 {@code IRouteService} 가 낸다.
 *
 * <h3>왜 인터페이스인가</h3>
 * {@code IBusClient} 를 그렇게 만들어 둔 것과 같다. 지금 구현체는 Gemini 하나지만,
 * 한도에 걸리거나 응답이 없을 때 규칙 기반으로 떨어질 자리가 필요하다.
 */
public interface ILlmClient {

    /**
     * 알아낸 목적지.
     *
     * @param destination 목적지 이름. {@code matched} 가 거짓이면 의미 없는 값이다
     * @param matched     안내할 수 있는 목적지를 찾았는가. <b>거짓이 정상 응답이다</b> —
     *                    "부산역 가고 싶어요" 처럼 안내 지역 밖을 말하면 엉뚱한 곳을
     *                    억지로 고르는 대신 여기서 거짓을 준다
     */
    record Destination(String destination, boolean matched) {
    }

    /**
     * 말 한 마디에서 목적지를 뽑는다.
     *
     * <p><b>후보 목록이 핵심이다.</b> 전사가 깨져도({@code 청주 신의청}) 후보에 정답이 있으면
     * 모델이 복구한다 — 2026-08-21 실측 7/7. 카카오 검색은 이걸 못 해준다
     * ({@code 청주 신의청} → 0건, {@code 시청 신청사} → <b>평택</b>시청).
     * 그래서 이 부품은 있으면 좋은 것이 아니라 <b>없으면 안 되는 것</b>이다.
     *
     * @param utterance  사용자가 한 말 그대로. 전사가 깨져 있을 수 있다
     * @param candidates 고를 수 있는 이름들. 비어 있으면 모델이 문장에서 직접 뽑는다
     *
     * @throws LlmUnavailableException 키가 없거나, 한도(429)거나, 응답이 없을 때.
     *         부르는 쪽이 규칙 기반으로 떨어질 수 있게 <b>예외로 구분해서</b> 알린다
     */
    Destination extractDestination(String utterance, List<String> candidates);
}
