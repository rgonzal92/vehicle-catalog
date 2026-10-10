package dev.rgonz.catalog.document;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.core.RequiresRole;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.document.Documents.Listed;
import dev.rgonz.catalog.user.AppUsers;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Lets admins upload documents, see the ones there are, and delete them. */
@RestController
class DocumentController {
  private final Documents documents;
  private final AppUsers people;

  DocumentController(Documents documents, AppUsers people) {
    this.documents = documents;
    this.people = people;
  }

  /** Every document, newest first. */
  @GetMapping("/api/documents")
  @RequiresRole(Role.ADMIN)
  List<Listed> list() {
    return documents.all();
  }

  /** Takes in one document about a vehicle line's model year, as a form with its file. */
  @PostMapping("/api/documents")
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.CREATED)
  Listed upload(
      @RequestParam MultipartFile file,
      @RequestParam(defaultValue = "") String title,
      @RequestParam long vehicleLineId,
      @RequestParam int modelYear,
      Authentication caller) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException unreadable) {
      throw ApiException.badRequest("The file did not arrive whole.");
    }
    var namedAs = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();

    return documents.add(people.idOf(caller), namedAs, bytes, title, vehicleLineId, modelYear);
  }

  @DeleteMapping("/api/documents/{id}")
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable long id) {
    documents.delete(id);
  }
}
