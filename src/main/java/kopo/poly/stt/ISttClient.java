package kopo.poly.stt;

/**
 * 소리를 글로 바꾼다.
 *
 * <h3>왜 인터페이스인가</h3>
 * {@code IBusClient} · {@code ILlmClient} 와 같은 이유다. 지금 구현체는 whisper.cpp 하나지만
 * 갈아탈 여지를 남긴다 — 특히 <b>Web Speech API</b> 가 후보로 남아 있다.
 *
 * <p>속도만 보면 Web Speech 가 이긴다(거의 0초 vs 1.9초, 설치 0, 서버 프로세스 0).
 * 그런데도 whisper 로 간 이유는 속도가 아니라 <b>통제</b>다.
 * <ul>
 *   <li>음성이 우리 손을 안 떠난다 — 실사용자를 받으면 이게 커진다</li>
 *   <li>구글이 API 를 바꾸거나 닫아도 안 죽는다</li>
 *   <li>오프라인에서도 된다</li>
 * </ul>
 * 지도 API 를 "블랙박스라 못 쓴다" 고 물리친 프로젝트라 성격이 맞는다.
 * <b>다만 시연 매끄러움이 목표가 되면 뒤집힐 수 있는 판단이다.</b> 그때 이 인터페이스가 값을 한다.
 */
public interface ISttClient {

    /**
     * WAV 를 글로.
     *
     * <p><b>16kHz · mono · 16bit PCM</b> 을 받는다. 브라우저에서 그렇게 만들어 보낸다 —
     * whisper 가 다른 형식을 받으려면 서버에 ffmpeg 가 있어야 하는데({@code --convert}),
     * 팀원마다 깔아야 하는 것을 하나라도 줄이는 편이 낫다.
     *
     * @return 전사문. 앞뒤 공백은 정리해서 준다. 아무 말도 못 알아들었으면 빈 문자열
     *
     * @throws SttUnavailableException 서버가 안 떠 있거나 응답하지 않을 때.
     *         '못 알아들었다'(빈 문자열)와 구분해야 한다 — 화면이 할 말이 다르다
     */
    String transcribe(byte[] wav);

    /**
     * 지금 쓸 수 있는 상태인가.
     *
     * <p>화면이 마이크 버튼을 열어둘지 정하는 데 쓴다. whisper 를 안 띄운 팀원에게
     * 마이크를 눌러보게 한 뒤에야 안 된다고 말하면, 그 사람은 자기 마이크를 의심한다.
     */
    boolean available();
}
