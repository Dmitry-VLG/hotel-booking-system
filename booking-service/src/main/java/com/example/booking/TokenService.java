package com.example.booking;

import com.example.booking.BookingModel.UserEntity;
import com.example.common.JwtSupport;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenService {
    private final JwtEncoder encoder;
    private final Clock clock;
    public BookingDtos.Token user(UserEntity user) {
        var now = clock.instant();
        List<String> roles = "ADMIN".equals(user.getRole()) ? List.of("USER", "ADMIN") : List.of("USER");
        var claims = JwtClaimsSet.builder().issuer(JwtSupport.USER_ISSUER).subject(user.getId().toString())
                .audience(List.of(JwtSupport.USER_AUDIENCE)).issuedAt(now).notBefore(now)
                .expiresAt(now.plusSeconds(3600)).claim("roles", roles).build();
        return new BookingDtos.Token(encode(claims), "Bearer", 3600);
    }
    public String internal() {
        var now = clock.instant();
        return encode(JwtClaimsSet.builder().issuer(JwtSupport.INTERNAL_ISSUER).subject("booking-service")
                .audience(List.of(JwtSupport.INTERNAL_AUDIENCE)).issuedAt(now).notBefore(now)
                .expiresAt(now.plusSeconds(60)).claim("scope", "inventory.write").build());
    }
    private String encode(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId("local-key").build(), claims)).getTokenValue();
    }
}
