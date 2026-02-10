package com.devappmobile.flowfuel.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {
    
    private final UserRepository userRepository;
    
    public ResponseEntity<?> register(User user) {
        // RF001.1 - Cadastro com validações
        // Validar email único
        if (userRepository.findByEmail(user.getEmail()).isPresent()) {
            return ResponseEntity.badRequest().body("Email já cadastrado");
        }
        
        // Validar senha mínima 6 caracteres
        if (user.getPassword().length() < 6) {
            return ResponseEntity.badRequest().body("Senha deve ter pelo menos 6 caracteres");
        }
        
        User savedUser = userRepository.save(user);
        return ResponseEntity.ok(savedUser);
    }
    
    public ResponseEntity<?> login(String email, String password) {
        // RF001.2 - Login
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isPresent() && user.get().getPassword().equals(password)) {
            return ResponseEntity.ok(user.get());
        }
        return ResponseEntity.badRequest().body("Email ou senha inválidos");
    }
    
    public ResponseEntity<?> sendPasswordReset(String email) {
        // RF001.2 - Recuperação de senha
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isPresent()) {
            // Lógica para enviar email de recuperação
            return ResponseEntity.ok("Email de recuperação enviado");
        }
        return ResponseEntity.badRequest().body("Email não encontrado");
    }
    
    public ResponseEntity<User> getUserProfile(Long userId) {
        // RF001.3 - Obter perfil
        Optional<User> user = userRepository.findById(userId);
        return user.map(ResponseEntity::ok)
                  .orElse(ResponseEntity.notFound().build());
    }
    
    public ResponseEntity<User> updateUserProfile(Long userId, User userDetails) {
        // RF001.3 - Editar perfil
        Optional<User> userOptional = userRepository.findById(userId);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            
            // Atualizar campos permitidos
            if (userDetails.getName() != null) {
                user.setName(userDetails.getName());
            }
            if (userDetails.getPhone() != null) {
                user.setPhone(userDetails.getPhone());
            }
            if (userDetails.getEmail() != null && 
                !userDetails.getEmail().equals(user.getEmail())) {
                // Validar se novo email não existe
                if (userRepository.findByEmail(userDetails.getEmail()).isEmpty()) {
                    user.setEmail(userDetails.getEmail());
                } else {
                    return ResponseEntity.badRequest().build();
                }
            }
            
            User updatedUser = userRepository.save(user);
            return ResponseEntity.ok(updatedUser);
        }
        return ResponseEntity.notFound().build();
    }
    
    public ResponseEntity<?> uploadProfilePicture(Long userId, MultipartFile file) {
        // RF001.3 - Upload foto (implementação básica)
        Optional<User> userOptional = userRepository.findById(userId);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            
            // Aqui você salvaria o arquivo e guardaria o caminho
            String filePath = "profile_pictures/" + userId + "_" + file.getOriginalFilename();
            user.setProfilePicture(filePath);
            
            userRepository.save(user);
            return ResponseEntity.ok("Foto atualizada com sucesso");
        }
        return ResponseEntity.notFound().build();
    }
    
    public ResponseEntity<?> deleteUser(Long userId) {
        // RF001.3 - Excluir conta
        Optional<User> user = userRepository.findById(userId);
        if (user.isPresent()) {
            userRepository.deleteById(userId);
            return ResponseEntity.ok("Conta excluída com sucesso");
        }
        return ResponseEntity.notFound().build();
    }
}