# The image registry. CI pushes every tested image here (spec section 6); EKS pulls from it.
resource "aws_ecr_repository" "app" {
  name = "sports-notifier"
  # A PR's moving tag (pr<N>) is re-pointed on every push to the PR.
  image_tag_mutability = "MUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 20 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 20
      }
      action = { type = "expire" }
    }]
  })
}
