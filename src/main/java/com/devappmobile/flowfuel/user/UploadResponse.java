package com.devappmobile.flowfuel.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {
    private String internalUrl; // /auth/{userId}/profile-picture
    private String signedUrl; // presigned S3 url (may be null if not available)
}
