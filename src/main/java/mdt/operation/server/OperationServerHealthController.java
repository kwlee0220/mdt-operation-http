package mdt.operation.server;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;


/**
 * 서버가 요청을 받을 수 있는지 확인하는 상태 확인 컨트롤러. ({@code GET /health})
 * <p>
 * Docker 이미지의 {@code HEALTHCHECK}에서 쓴다. MDT 관리자 연결이나 연산 디렉터리는 확인하지 않는다.
 * mdt-manager·mdt-workflow의 {@code /health}와 같은 형식이다(검토 I4).
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Tag(name = "상태 확인 API")
@RestController
public class OperationServerHealthController {
	@Operation(summary = "서버가 동작 중인지 확인한다.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "성공",
					content = {@Content(mediaType = "text/plain", schema = @Schema(type = "string"))})
	})
	@GetMapping("/health")
	public String health() {
		return "ok";
	}
}
