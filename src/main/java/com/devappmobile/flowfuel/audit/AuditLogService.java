package com.devappmobile.flowfuel.audit;

import com.devappmobile.flowfuel.common.ClientIpResolver;
import com.devappmobile.flowfuel.common.PageResponseDTO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Registro best-effort de ações sensíveis de usuário. Nunca propaga exceção:
 * uma falha de auditoria (Redis/DB indisponível, fora de contexto de
 * requisição) não pode quebrar o fluxo de negócio que a originou.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;

    public void record(Long userId, AuditAction action) {
        try {
            auditLogRepository.save(new AuditLog(userId, action, currentClientIp()));
        } catch (Exception e) {
            log.warn("Falha ao gravar audit log userId={} action={} error={}",
                    userId, action, e.getMessage());
        }
    }

    public PageResponseDTO<AuditLogResponseDTO> search(
            Long userId, AuditAction action, Pageable pageable) {
        Page<AuditLog> page;
        if (userId != null && action != null) {
            page = auditLogRepository.findByUserIdAndActionOrderByCreatedAtDesc(userId, action, pageable);
        } else if (userId != null) {
            page = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        } else if (action != null) {
            page = auditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable);
        } else {
            page = auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return PageResponseDTO.from(page, AuditLogResponseDTO::from);
    }

    private String currentClientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return ClientIpResolver.resolve(request);
    }
}
