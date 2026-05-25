package com.ivdr.domain.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Service responsible for sending email notifications including OTP verification.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    /**
     * Sends an OTP verification email to the given address.
     *
     * @param toEmail   recipient email address
     * @param otp       6-digit OTP code
     * @param fullName  recipient's full name for personalization
     * @param orgName   organization name for context
     */
    @Async
    public void sendOtpEmail(String toEmail, String otp, String fullName, String orgName) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("IVDR — Verify Your Email Address");
            helper.setText(buildOtpEmailHtml(fullName, otp, orgName), true);

            mailSender.send(message);
            log.info("OTP email sent to: {}", toEmail);
        } catch (MessagingException ex) {
            log.error("Failed to send OTP email to {}: {}", toEmail, ex.getMessage(), ex);
        }
    }

    /**
     * Sends a welcome email after successful registration.
     *
     * @param toEmail  recipient email address
     * @param fullName recipient's full name
     * @param orgName  organization name
     */
    @Async
    public void sendWelcomeEmail(String toEmail, String fullName, String orgName) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Welcome to IVDR — " + orgName);
            helper.setText(buildWelcomeEmailHtml(fullName, orgName), true);

            mailSender.send(message);
            log.info("Welcome email sent to: {}", toEmail);
        } catch (MessagingException ex) {
            log.error("Failed to send welcome email to {}: {}", toEmail, ex.getMessage(), ex);
        }
    }

    /**
     * Sends a workspace invitation email.
     *
     * @param toEmail       recipient email address
     * @param inviterName   name of person who sent invitation
     * @param workspaceName name of the workspace
     * @param role          assigned role
     */
    @Async
    public void sendInvitationEmail(String toEmail, String inviterName, String workspaceName, String role) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("IVDR — You've been invited to " + workspaceName);
            helper.setText(buildInvitationEmailHtml(inviterName, workspaceName, role), true);

            mailSender.send(message);
            log.info("Invitation email sent to: {} for workspace: {}", toEmail, workspaceName);
        } catch (MessagingException ex) {
            log.error("Failed to send invitation email to {}: {}", toEmail, ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Email HTML builders
    // -------------------------------------------------------------------------

    private String buildOtpEmailHtml(String fullName, String otp, String orgName) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <style>
                    body { font-family: Inter, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 20px; }
                    .container { max-width: 560px; margin: 0 auto; background: rgba(30,41,59,0.9); border-radius: 16px;
                                 border: 1px solid rgba(255,255,255,0.08); overflow: hidden; }
                    .header { background: linear-gradient(135deg, #3b82f6, #10b981); padding: 32px; text-align: center; }
                    .header h1 { margin: 0; font-size: 24px; color: #fff; }
                    .body { padding: 32px; }
                    .otp-box { background: rgba(59,130,246,0.1); border: 1px solid rgba(59,130,246,0.3);
                               border-radius: 12px; padding: 24px; text-align: center; margin: 24px 0; }
                    .otp-code { font-size: 48px; font-weight: 700; letter-spacing: 12px;
                                background: linear-gradient(135deg, #3b82f6, #10b981);
                                -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
                    .footer { padding: 16px 32px; text-align: center; color: #94a3b8; font-size: 12px;
                              border-top: 1px solid rgba(255,255,255,0.08); }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <div class="header">
                      <h1> IVDR Compliance Portal</h1>
                    </div>
                    <div class="body">
                      <p>Hello <strong>%s</strong>,</p>
                      <p>You are registering the organization <strong>%s</strong> on IVDR Compliance Portal.</p>
                      <p>Please use the following One-Time Password (OTP) to verify your email address:</p>
                      <div class="otp-box">
                        <div class="otp-code">%s</div>
                        <p style="color:#94a3b8; margin-top: 12px; font-size: 14px;">Valid for 10 minutes</p>
                      </div>
                      <p style="color:#94a3b8; font-size: 14px;">If you did not request this, please ignore this email.</p>
                    </div>
                    <div class="footer">© 2026 IVDR Compliance Portal. All rights reserved.</div>
                  </div>
                </body>
                </html>
                """.formatted(fullName, orgName, otp);
    }

    private String buildWelcomeEmailHtml(String fullName, String orgName) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <style>
                    body { font-family: Inter, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 20px; }
                    .container { max-width: 560px; margin: 0 auto; background: rgba(30,41,59,0.9); border-radius: 16px;
                                 border: 1px solid rgba(255,255,255,0.08); overflow: hidden; }
                    .header { background: linear-gradient(135deg, #10b981, #3b82f6); padding: 32px; text-align: center; }
                    .header h1 { margin: 0; font-size: 24px; color: #fff; }
                    .body { padding: 32px; }
                    .footer { padding: 16px 32px; text-align: center; color: #94a3b8; font-size: 12px;
                              border-top: 1px solid rgba(255,255,255,0.08); }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <div class="header"><h1>🎉 Welcome to IVDR!</h1></div>
                    <div class="body">
                      <p>Hello <strong>%s</strong>,</p>
                      <p>Your organization <strong>%s</strong> has been successfully created on IVDR Compliance Portal.</p>
                      <p>You can now:</p>
                      <ul>
                        <li>Create secure Deal Room workspaces</li>
                        <li>Upload and manage compliance documents</li>
                        <li>Invite team members with role-based access</li>
                        <li>Monitor activity with real-time analytics</li>
                      </ul>
                    </div>
                    <div class="footer">© 2026 IVDR Compliance Portal. All rights reserved.</div>
                  </div>
                </body>
                </html>
                """.formatted(fullName, orgName);
    }

    private String buildInvitationEmailHtml(String inviterName, String workspaceName, String role) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <style>
                    body { font-family: Inter, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 20px; }
                    .container { max-width: 560px; margin: 0 auto; background: rgba(30,41,59,0.9); border-radius: 16px;
                                 border: 1px solid rgba(255,255,255,0.08); overflow: hidden; }
                    .header { background: linear-gradient(135deg, #ec4899, #3b82f6); padding: 32px; text-align: center; }
                    .header h1 { margin: 0; font-size: 24px; color: #fff; }
                    .body { padding: 32px; }
                    .role-badge { display: inline-block; background: rgba(16,185,129,0.15); color: #10b981;
                                  border: 1px solid rgba(16,185,129,0.3); border-radius: 20px; padding: 4px 16px;
                                  font-weight: 600; }
                    .footer { padding: 16px 32px; text-align: center; color: #94a3b8; font-size: 12px;
                              border-top: 1px solid rgba(255,255,255,0.08); }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <div class="header"><h1>📁 Workspace Invitation</h1></div>
                    <div class="body">
                      <p><strong>%s</strong> has invited you to join the workspace:</p>
                      <h2 style="color: #10b981;">%s</h2>
                      <p>Your assigned role: <span class="role-badge">%s</span></p>
                      <p style="color:#94a3b8; font-size: 14px;">Log in to IVDR to access the workspace.</p>
                    </div>
                    <div class="footer">© 2026 IVDR Compliance Portal. All rights reserved.</div>
                  </div>
                </body>
                </html>
                """.formatted(inviterName, workspaceName, role);
    }
}
