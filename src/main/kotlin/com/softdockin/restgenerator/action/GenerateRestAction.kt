package com.softdockin.restgenerator.action

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import java.io.File
import java.util.Locale

class GenerateRestAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)

        if (project == null || psiFile == null) {
            Messages.showMessageDialog(project, "No file selected", "Error", Messages.getErrorIcon())
            return
        }

        val psiClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
        val ktClass = PsiTreeUtil.findChildOfType(psiFile, KtClass::class.java)

        val className = when {
            psiClass != null -> psiClass.name
            ktClass != null -> ktClass.name
            else -> null
        }

        val classQualifiedName = when {
            psiClass != null -> psiClass.qualifiedName
            ktClass != null -> ktClass.fqName?.asString()
            else -> null
        }

        if (className == null || classQualifiedName == null) {
            Messages.showMessageDialog(project, "No class found in the selected file.", "Error", Messages.getErrorIcon())
            return
        }

        WriteCommandAction.runWriteCommandAction(project) {
            val baseDir = psiFile.containingDirectory.parentDirectory
            generateRepository(project, baseDir, className, classQualifiedName)
            generateDto(project, baseDir, className, ktClass, psiClass)
            generateRequestModels(project, baseDir, className, ktClass, psiClass)
            generateMapper(project, baseDir, className, classQualifiedName)
            generateService(project, baseDir, className)
            generateController(project, baseDir, className)
        }

        Messages.showMessageDialog(project, "Generating REST components for $className", "In Progress", Messages.getInformationIcon())
    }

    private fun generateRepository(project: Project, baseDir: PsiDirectory?, className: String, classQualifiedName: String) {
        val repositoryPackageName = getPackageName(baseDir) + ".data.access"
        val repositoryDir = createDirectories(baseDir, "data/access")

        val repositoryContent = """
            package $repositoryPackageName;

            import $classQualifiedName;
            import org.springframework.data.jpa.repository.JpaRepository;
            import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
            import org.springframework.stereotype.Repository;

            @Repository
            public interface ${className}Repository extends JpaRepository<$className, Long>, JpaSpecificationExecutor<$className> {
            }
        """.trimIndent()

        val psiFileFactory = PsiFileFactory.getInstance(project)
        val repositoryFile = psiFileFactory.createFileFromText("${className}Repository.java", JavaLanguage.INSTANCE, repositoryContent)

        repositoryDir?.add(repositoryFile)
    }
    
    private fun generateDto(project: Project, baseDir: PsiDirectory?, className: String, ktClass: KtClass?, psiClass: PsiClass?) {
        val dtoPackageName = getPackageName(baseDir) + ".service.model"
        val dtoDir = createDirectories(baseDir, "service/model")
        val dtoName = "${className}Dto"

        val fields = when {
            ktClass != null -> ktClass.getProperties().joinToString("\n") { "    private ${it.typeReference?.text} ${it.name};" }
            psiClass != null -> psiClass.fields.joinToString("\n") { "    private ${it.type.presentableText} ${it.name};" }
            else -> ""
        }

        val dtoContent = """
            package $dtoPackageName;

            import lombok.Data;

            @Data
            public class $dtoName {
            $fields
            }
        """.trimIndent()

        val psiFileFactory = PsiFileFactory.getInstance(project)
        val dtoFile = psiFileFactory.createFileFromText("$dtoName.java", JavaLanguage.INSTANCE, dtoContent)

        dtoDir?.add(dtoFile)
    }
    
    private fun generateRequestModels(project: Project, baseDir: PsiDirectory?, className: String, ktClass: KtClass?, psiClass: PsiClass?) {
        val requestPackageName = getPackageName(baseDir) + ".web.model"
        val requestDir = createDirectories(baseDir, "web/model")
        val requestName = "${className}Request"
        val searchRequestName = "${className}SearchRequest"

        val fieldsWithValidation = when {
            ktClass != null -> ktClass.getProperties().joinToString("\n") { "    @NotNull\n    private ${it.typeReference?.text} ${it.name};" }
            psiClass != null -> psiClass.fields.joinToString("\n") { "    @NotNull\n    private ${it.type.presentableText} ${it.name};" }
            else -> ""
        }
        
        val fieldsForSearch = when {
            ktClass != null -> ktClass.getProperties().joinToString("\n") { "    private ${it.typeReference?.text} ${it.name};" }
            psiClass != null -> psiClass.fields.joinToString("\n") { "    private ${it.type.presentableText} ${it.name};" }
            else -> ""
        }

        val requestContent = """
            package $requestPackageName;
            
            import jakarta.validation.constraints.NotNull;
            import lombok.Data;

            @Data
            public class $requestName {
            $fieldsWithValidation
            }
        """.trimIndent()

        val searchRequestContent = """
            package $requestPackageName;

            import lombok.Data;

            @Data
            public class $searchRequestName {
            $fieldsForSearch
            }
        """.trimIndent()

        val psiFileFactory = PsiFileFactory.getInstance(project)
        val requestFile = psiFileFactory.createFileFromText("$requestName.java", JavaLanguage.INSTANCE, requestContent)
        val searchRequestFile = psiFileFactory.createFileFromText("$searchRequestName.java", JavaLanguage.INSTANCE, searchRequestContent)

        requestDir?.add(requestFile)
        requestDir?.add(searchRequestFile)
    }

    private fun generateMapper(project: Project, baseDir: PsiDirectory?, className: String, classQualifiedName: String) {
        val packageName = getPackageName(baseDir)
        val mapperPackageName = "$packageName.mapper"
        val mapperDir = createDirectories(baseDir, "mapper")
        val mapperName = "${className}Mapper"
        val requestName = "${className}Request"
        val dtoName = "${className}Dto"

        val mapperContent = """
            package $mapperPackageName;

            import $classQualifiedName;
            import $packageName.web.model.$requestName;
            import $packageName.service.model.$dtoName;
            import org.mapstruct.Mapper;
            import org.mapstruct.MappingTarget;
            import org.mapstruct.factory.Mappers;

            @Mapper(componentModel = "spring")
            public interface $mapperName {

                $mapperName INSTANCE = Mappers.getMapper($mapperName.class);

                $className toEntity($requestName request);

                void updateEntity($requestName request, @MappingTarget $className entity);
                
                $dtoName toDto($className entity);
            }
        """.trimIndent()

        val psiFileFactory = PsiFileFactory.getInstance(project)
        val mapperFile = psiFileFactory.createFileFromText("$mapperName.java", JavaLanguage.INSTANCE, mapperContent)

        mapperDir?.add(mapperFile)
    }

    private fun generateService(project: Project, baseDir: PsiDirectory?, className: String) {
        val packageName = getPackageName(baseDir)
        val servicePackageName = "$packageName.service"
        val serviceDir = createDirectories(baseDir, "service")
        val serviceName = "${className}Service"
        val repositoryName = "${className}Repository"
        val repositoryInstanceName = repositoryName.replaceFirstChar { it.lowercase(Locale.getDefault()) }
        val mapperName = "${className}Mapper"
        val mapperInstanceName = mapperName.replaceFirstChar { it.lowercase(Locale.getDefault()) }
        val requestName = "${className}Request"
        val searchRequestName = "${className}SearchRequest"
        val dtoName = "${className}Dto"

        val serviceContent = """
            package $servicePackageName;

            import $packageName.data.access.$repositoryName;
            import $packageName.mapper.$mapperName;
            import $packageName.web.model.$requestName;
            import $packageName.web.model.$searchRequestName;
            import $packageName.service.model.$dtoName;
            import $packageName.domain.$className;
            import lombok.RequiredArgsConstructor;
            import org.springframework.data.domain.Page;
            import org.springframework.stereotype.Service;
            import jakarta.persistence.EntityNotFoundException;

            @Service
            @RequiredArgsConstructor
            public class $serviceName {

                private final $repositoryName $repositoryInstanceName;
                private final $mapperName $mapperInstanceName;

                public $dtoName create($requestName request) {
                    $className entity = $mapperInstanceName.toEntity(request);
                    $className savedEntity = $repositoryInstanceName.save(entity);
                    return $mapperInstanceName.toDto(savedEntity);
                }

                public $dtoName update(Long id, $requestName request) {
                    $className entity = $repositoryInstanceName.findById(id)
                        .orElseThrow(() -> new EntityNotFoundException("$className not found with id: " + id));
                    $mapperInstanceName.updateEntity(request, entity);
                    $className updatedEntity = $repositoryInstanceName.save(entity);
                    return $mapperInstanceName.toDto(updatedEntity);
                }

                public void delete(Long id) {
                    $repositoryInstanceName.deleteById(id);
                }

                public $dtoName get(Long id) {
                    return $repositoryInstanceName.findById(id)
                        .map($mapperInstanceName::toDto)
                        .orElseThrow(() -> new EntityNotFoundException("$className not found with id: " + id));
                }

                public Page<$dtoName> search($searchRequestName searchRequest) {
                    // TODO: Implement specification-based search
                    // Example: Specification<$className> spec = new ${className}Specification(searchRequest);
                    // return $repositoryInstanceName.findAll(spec, pageable).map($mapperInstanceName::toDto);
                    throw new UnsupportedOperationException("Search not implemented yet");
                }
            }
        """.trimIndent()

        val psiFileFactory = PsiFileFactory.getInstance(project)
        val serviceFile = psiFileFactory.createFileFromText("$serviceName.java", JavaLanguage.INSTANCE, serviceContent)

        serviceDir?.add(serviceFile)
    }

    private fun generateController(project: Project, baseDir: PsiDirectory?, className: String) {
        val packageName = getPackageName(baseDir)
        val controllerPackageName = "$packageName.web"
        val controllerDir = createDirectories(baseDir, "web")
        val controllerName = "${className}Controller"
        val serviceName = "${className}Service"
        val serviceInstanceName = serviceName.replaceFirstChar { it.lowercase(Locale.getDefault()) }
        val requestName = "${className}Request"
        val searchRequestName = "${className}SearchRequest"
        val dtoName = "${className}Dto"
        val endpoint = className.replaceFirstChar { it.lowercase(Locale.getDefault()) }

        val controllerContent = """
            package $controllerPackageName;

            import $packageName.service.$serviceName;
            import $packageName.web.model.$requestName;
            import $packageName.web.model.$searchRequestName;
            import $packageName.service.model.$dtoName;
            import lombok.RequiredArgsConstructor;
            import org.springframework.data.domain.Page;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.*;
            import jakarta.validation.Valid;

            @RestController
            @RequestMapping("/api/$endpoint")
            @RequiredArgsConstructor
            public class $controllerName {

                private final $serviceName $serviceInstanceName;

                @PostMapping
                public ResponseEntity<$dtoName> create(@Valid @RequestBody $requestName request) {
                    return ResponseEntity.ok(this.$serviceInstanceName.create(request));
                }

                @PutMapping("/{id}")
                public ResponseEntity<$dtoName> update(@PathVariable Long id, @Valid @RequestBody $requestName request) {
                    return ResponseEntity.ok(this.$serviceInstanceName.update(id, request));
                }

                @DeleteMapping("/{id}")
                public ResponseEntity<Void> delete(@PathVariable Long id) {
                    this.$serviceInstanceName.delete(id);
                    return ResponseEntity.ok().build();
                }

                @GetMapping("/{id}")
                public ResponseEntity<$dtoName> get(@PathVariable Long id) {
                    return ResponseEntity.ok(this.$serviceInstanceName.get(id));
                }

                @PostMapping("/search")
                public ResponseEntity<Page<$dtoName>> search(@RequestBody $searchRequestName searchRequest) {
                    return ResponseEntity.ok(this.$serviceInstanceName.search(searchRequest));
                }
            }
        """.trimIndent()

        val psiFileFactory = PsiFileFactory.getInstance(project)
        val controllerFile = psiFileFactory.createFileFromText("$controllerName.java", JavaLanguage.INSTANCE, controllerContent)

        controllerDir?.add(controllerFile)
    }

    private fun getPackageName(directory: PsiDirectory?): String {
        directory ?: return ""
        val project = directory.project
        val projectRootManager = ProjectRootManager.getInstance(project)
        val sourceRoots = projectRootManager.contentSourceRoots
        val directoryPath = directory.virtualFile.path
        for (sourceRoot in sourceRoots) {
            if (directoryPath.startsWith(sourceRoot.path)) {
                return directoryPath.substring(sourceRoot.path.length + 1).replace(File.separator, ".")
            }
        }
        return ""
    }

    private fun createDirectories(baseDir: PsiDirectory?, path: String): PsiDirectory? {
        var currentDir = baseDir
        path.split("/").forEach { dirName ->
            currentDir = currentDir?.findSubdirectory(dirName) ?: currentDir?.createSubdirectory(dirName)
        }
        return currentDir
    }
}
