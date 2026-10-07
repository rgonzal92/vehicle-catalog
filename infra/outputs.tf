output "certificate_validation_record" {
  description = "The DNS record that proves to AWS who holds the site's name. It is added at Cloudflare, as a DNS-only record."
  value = one([for option in aws_acm_certificate.site.domain_validation_options : {
    name  = option.resource_record_name
    type  = option.resource_record_type
    value = option.resource_record_value
  }])
}

output "host_address" {
  description = "The host's fixed address: an A record at Cloudflare, DNS-only, from origin-catalog to this."
  value       = aws_eip.host.public_ip
}

output "site_dns_target" {
  description = "Where the site's name points: a CNAME record at Cloudflare, DNS-only, from the site's name to this."
  value       = aws_cloudfront_distribution.site.domain_name
}
